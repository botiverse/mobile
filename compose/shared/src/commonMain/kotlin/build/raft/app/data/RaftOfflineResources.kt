package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Offline-first resources (artin: everything fetched is stored locally; persistence audit item 4).
 *
 * One process-wide [RaftOfflineResourceCache] owns every resource's durable rows, in ONE
 * `localSnapshotEntity` scope, so:
 * - sign-out / account switch keeps every row (rows are keyed by account, so only the same account
 *   reads them again) and only fences in-flight writes ([fenceInFlightWrites]) (owner decision 2026-10-27,
 *   see AccountLocalData); [wipeAll] / [wipeAllAsync] remove every resource with one call, rows of a
 *   resource this process never instantiated included;
 * - lost access to a server wipes that server's rows of every resource ([wipeServer]).
 *
 * A resource ([resource]) is keyed by environment + account + server + resource name + params
 * ([RaftOfflineResourceKey]); values are encoded with the resource's kotlinx-serialization codec. Each
 * resource has its own caps (entries per account, bytes per entry, optional max age).
 *
 * Reads go through MobileNativeFoundation Store5 ([RaftOfflineResource.store]): the rows are Store5's
 * source of truth and the consumer's request is its fetcher; [RaftOfflineResourceStore.revalidate] is the
 * stale-while-revalidate read (stored value first as a stand-in, then exactly one network answer).
 * Concurrent revalidations of one key share one request. Store5's memory cache is off: the rows are the
 * single source of truth.
 *
 * Ordering, on one logical clock ([tick]); every row operation runs under one mutex:
 * - a key remembers when it was minted; a wipe fences only the keys it covers (every account, one server,
 *   or one account's server) minted before it, so a request of a wiped account (or of a lost server)
 *   cannot write, and other servers' requests are unaffected;
 * - a fetch remembers when it started; a confirmed mutation ([RaftOfflineResource.update] /
 *   [RaftOfflineResource.remove]) stamps its row, and a fetch that started before that stamp does not write
 *   its older answer over the mutation;
 * - a wipe is queued synchronously and runs before any later read or write (also [wipeAllAsync], which
 *   is called from a non-suspending cleanup), so it never deletes a later write.
 */
internal class RaftOfflineResourceCache(
    private val rows: RaftOfflineResourceRows,
    private val environmentId: () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    // Guards the clock, the fences and the wipe queue (non-suspending: wipeAllAsync and key minting).
    private val fenceLock = SynchronizedObject()
    private var clock = 0L
    private var wipedAllAt = NeverTick
    private var fencedAllAt = NeverTick
    private val serverWipedAt = mutableMapOf<String, Long>()
    private val accountServerWipedAt = mutableMapOf<Pair<String, String>, Long>()
    private val rowChangedAt = mutableMapOf<String, Long>()
    private val pendingWipes = ArrayDeque<Wipe>()

    internal val storeScope: CoroutineScope
        get() = scope

    fun <P : Any, T : Any> resource(spec: RaftOfflineResourceSpec<P, T>): RaftOfflineResource<P, T> =
        RaftOfflineResource(this, spec)

    /** Every row of every resource; in-flight writes are fenced. */
    suspend fun wipeAll() {
        enqueueWipe(Wipe.All)
        mutex.withLock { drainWipes() }
    }

    /**
     * For a non-suspending caller: fences and queues the wipe now (every later read and
     * write runs after it), deletes in the background.
     */
    fun wipeAllAsync() {
        enqueueWipe(Wipe.All)
        scope.launch { mutex.withLock { drainWipes() } }
    }

    /**
     * Sign-out keeps every row (they are keyed by account; owner decision 2026-10-27): only writes of keys
     * minted before this call (an in-flight request of the signed-out session) are fenced. Non-suspending.
     */
    fun fenceInFlightWrites() {
        synchronized(fenceLock) { fencedAllAt = ++clock }
    }

    /**
     * Lost access to [serverId] ([accountId] null = every account): that server's rows of every resource.
     * Fences only that server's (and account's) in-flight writes.
     */
    suspend fun wipeServer(serverId: String, accountId: String? = null) {
        val wipe = serverWipe(serverId, accountId) ?: return
        enqueueWipe(wipe)
        mutex.withLock { drainWipes() }
    }

    fun wipeServerAsync(serverId: String, accountId: String? = null) {
        val wipe = serverWipe(serverId, accountId) ?: return
        enqueueWipe(wipe)
        scope.launch { mutex.withLock { drainWipes() } }
    }

    /** The next instant of the cache's logical clock. */
    internal fun tick(): Long = synchronized(fenceLock) { ++clock }

    internal fun mintKey(accountId: String, serverId: String): Triple<String, String, String>? {
        val environment = environmentId()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val account = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        return Triple(environment, account, server)
    }

    internal suspend fun read(rowId: String, maxAgeMillis: Long?): String? =
        mutex.withLock {
            drainWipes()
            readLocked(rowId, maxAgeMillis)
        }

    /**
     * Writes [encoded] for [rowId] unless a wipe covering it happened after [mintedAt] or a confirmed mutation
     * of the row happened after [startedAt]; drops the oldest rows of the same resource + environment +
     * account past [maxEntriesPerAccount] in the same transaction.
     */
    internal suspend fun write(
        rowId: String,
        encoded: String?,
        mintedAt: Long,
        startedAt: Long,
        maxEntriesPerAccount: Int,
    ): RaftOfflineWriteOutcome =
        mutex.withLock {
            drainWipes()
            val parts = RaftOfflineRowId.parts(rowId) ?: return@withLock RaftOfflineWriteOutcome.Failed
            if (wipedSince(parts, mintedAt)) {
                logger("offline_resource_write result=fenced")
                return@withLock RaftOfflineWriteOutcome.Fenced
            }
            if (changedSince(rowId, startedAt)) {
                logger("offline_resource_write result=superseded")
                return@withLock RaftOfflineWriteOutcome.Superseded
            }
            when {
                // Too large to store: the stale copy goes (under the same fences as a write).
                encoded == null -> {
                    runLogged("delete") { rows.delete(listOf(rowId)) }
                    RaftOfflineWriteOutcome.Failed
                }
                writeLocked(rowId, parts, encoded, maxEntriesPerAccount) -> RaftOfflineWriteOutcome.Written
                else -> RaftOfflineWriteOutcome.Failed
            }
        }

    /**
     * A confirmed mutation of [rowId]: [change] maps the stored encoding (absent / expired: not called, false)
     * to its replacement, all under the lock, and stamps the row so an older in-flight fetch cannot overwrite
     * it. Fenced (false) when a wipe covering the row happened after [mintedAt].
     */
    internal suspend fun update(
        rowId: String,
        mintedAt: Long,
        maxAgeMillis: Long?,
        maxEntriesPerAccount: Int,
        change: (String) -> RaftOfflineChange,
    ): Boolean =
        mutex.withLock {
            drainWipes()
            val parts = RaftOfflineRowId.parts(rowId) ?: return@withLock false
            if (wipedSince(parts, mintedAt)) {
                logger("offline_resource_update result=fenced")
                return@withLock false
            }
            // Stamped even with nothing stored: an older in-flight fetch must not write a pre-mutation value.
            markChanged(rowId)
            val current = readLocked(rowId, maxAgeMillis) ?: return@withLock false
            when (val next = change(current)) {
                is RaftOfflineChange.Write -> writeLocked(rowId, parts, next.encoded, maxEntriesPerAccount)
                RaftOfflineChange.Remove -> {
                    runLogged("delete") { rows.delete(listOf(rowId)) }
                    true
                }
            }
        }

    /** Deletes [rowId]; [confirmed]: a confirmed change (an older in-flight fetch cannot write it back). */
    internal suspend fun delete(rowId: String, confirmed: Boolean) {
        mutex.withLock {
            drainWipes()
            if (confirmed) markChanged(rowId)
            runLogged("delete") { rows.delete(listOf(rowId)) }
        }
    }

    private suspend fun readLocked(rowId: String, maxAgeMillis: Long?): String? {
        val row =
            try {
                rows.read(rowId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("offline_resource_read result=failure type=${error::class.simpleName}")
                null
            } ?: return null
        if (maxAgeMillis != null && nowMillis() - row.updatedAtMs > maxAgeMillis) {
            runLogged("expire") { rows.delete(listOf(rowId)) }
            return null
        }
        return row.value
    }

    private suspend fun writeLocked(
        rowId: String,
        parts: RaftOfflineRowId.Parts,
        encoded: String,
        maxEntriesPerAccount: Int,
    ): Boolean {
        val group = parts.accountGroup
        val cap = maxEntriesPerAccount.coerceAtLeast(1)
        return try {
            rows.writeAndEvict(rowId, encoded, nowMillis()) { metas ->
                metas
                    .filter { RaftOfflineRowId.parts(it.id)?.accountGroup == group }
                    .sortedByDescending { it.updatedAtMs }
                    .drop(cap)
                    .map { it.id }
                    .filterNot { it == rowId }
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("offline_resource_write result=failure type=${error::class.simpleName}")
            false
        }
    }

    private fun serverWipe(serverId: String, accountId: String?): Wipe.Server? {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        return Wipe.Server(server, accountId?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Fences the wipe's keys and queues its delete, synchronously. */
    private fun enqueueWipe(wipe: Wipe) {
        synchronized(fenceLock) {
            val at = ++clock
            when (wipe) {
                Wipe.All -> {
                    wipedAllAt = at
                    serverWipedAt.clear()
                    accountServerWipedAt.clear()
                    rowChangedAt.clear()
                }
                is Wipe.Server ->
                    if (wipe.accountId == null) {
                        serverWipedAt[wipe.serverId] = at
                    } else {
                        accountServerWipedAt[wipe.accountId to wipe.serverId] = at
                    }
            }
            pendingWipes.addLast(wipe)
        }
    }

    /** Runs every queued wipe, in order; called under [mutex] before any other row operation. */
    private suspend fun drainWipes() {
        while (true) {
            val wipe = synchronized(fenceLock) { pendingWipes.removeFirstOrNull() } ?: return
            // Whoever drains (often a UI read that may be cancelled) finishes the wipe: once dequeued it must run.
            withContext(NonCancellable) { runWipe(wipe) }
        }
    }

    private suspend fun runWipe(wipe: Wipe) {
        when (wipe) {
            Wipe.All -> runLogged("wipe_all") { rows.deleteAll() }
            is Wipe.Server ->
                runLogged("wipe_server") {
                    val victims =
                        rows.ids().filter { id ->
                            val parts = RaftOfflineRowId.parts(id) ?: return@filter false
                            parts.serverId == wipe.serverId && (wipe.accountId == null || parts.accountId == wipe.accountId)
                        }
                    rows.delete(victims)
                }
        }
    }

    private fun wipedSince(parts: RaftOfflineRowId.Parts, mintedAt: Long): Boolean =
        synchronized(fenceLock) {
            wipedAllAt > mintedAt ||
                fencedAllAt > mintedAt ||
                (serverWipedAt[parts.serverId] ?: NeverTick) > mintedAt ||
                (accountServerWipedAt[parts.accountId to parts.serverId] ?: NeverTick) > mintedAt
        }

    private fun changedSince(rowId: String, startedAt: Long): Boolean =
        synchronized(fenceLock) { (rowChangedAt[rowId] ?: NeverTick) > startedAt }

    private fun markChanged(rowId: String) {
        synchronized(fenceLock) { rowChangedAt[rowId] = ++clock }
    }

    private suspend fun runLogged(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("offline_resource_$operation result=failure type=${error::class.simpleName}")
        }
    }

    private sealed interface Wipe {
        data object All : Wipe

        data class Server(val serverId: String, val accountId: String?) : Wipe
    }

    private companion object {
        // Before every instant of the clock (which starts at 1).
        const val NeverTick = 0L
    }
}

internal enum class RaftOfflineWriteOutcome {
    Written,

    /** A wipe covering the row happened after the key was minted. */
    Fenced,

    /** A confirmed mutation of the row happened after the request started: the stored value is newer. */
    Superseded,

    /** Too large, or the write failed. */
    Failed,
}

/** What a confirmed mutation does to the stored encoding ([RaftOfflineResourceCache.update]). */
internal sealed interface RaftOfflineChange {
    data class Write(val encoded: String) : RaftOfflineChange

    data object Remove : RaftOfflineChange
}

/**
 * One kind of offline resource.
 *
 * @param name stable row prefix (bump [version] when the stored shape changes incompatibly).
 * @param paramsId the params' part of the row id (empty for a resource with one value per server).
 * @param maxEntriesPerAccount rows kept per environment + account (every server / params), newest first.
 * @param maxBytesPerEntry a value whose encoding is larger is not stored (the stale copy is dropped).
 * @param maxAgeMillis a stored value older than this reads as absent; null = no age limit.
 * @param fit trims a value before it is stored (e.g. the first page of a list).
 */
internal class RaftOfflineResourceSpec<P : Any, T : Any>(
    val name: String,
    val version: Int,
    val serializer: KSerializer<T>,
    val paramsId: (P) -> String,
    val maxEntriesPerAccount: Int,
    val maxBytesPerEntry: Int,
    val maxAgeMillis: Long? = null,
    val fit: (T) -> T = { it },
)

/**
 * Environment + account + server + params of one stored value; minted by [RaftOfflineResource.key].
 * [mintedAt] (when it was minted, on the cache's clock) is not part of equality: keys minted for the same
 * value at different times share one Store5 request.
 */
internal data class RaftOfflineResourceKey<P : Any>(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val params: P,
) {
    internal var mintedAt: Long = 0L
        private set

    internal constructor(
        environmentId: String,
        accountId: String,
        serverId: String,
        params: P,
        mintedAt: Long,
    ) : this(environmentId, accountId, serverId, params) {
        this.mintedAt = mintedAt
    }
}

internal class RaftOfflineResource<P : Any, T : Any>(
    private val cache: RaftOfflineResourceCache,
    private val spec: RaftOfflineResourceSpec<P, T>,
) {
    /** The key of ([accountId], [serverId], [params]) under the current environment, minted now. */
    fun key(accountId: String, serverId: String, params: P): RaftOfflineResourceKey<P>? {
        val (environment, account, server) = cache.mintKey(accountId, serverId) ?: return null
        return RaftOfflineResourceKey(environment, account, server, params, mintedAt = cache.tick())
    }

    suspend fun read(key: RaftOfflineResourceKey<P>): T? {
        val rowId = rowId(key) ?: return null
        val encoded = cache.read(rowId, spec.maxAgeMillis) ?: return null
        val decoded = decodeOrNull(encoded)
        if (decoded == null) cache.delete(rowId, confirmed = false)
        return decoded
    }

    /**
     * Stores [value] (after [RaftOfflineResourceSpec.fit]); false when fenced (wiped since [key] was minted,
     * or a confirmed mutation since [startedAt]), too large or failed. [startedAt]: when the request that
     * produced [value] started (default: when [key] was minted).
     */
    suspend fun write(key: RaftOfflineResourceKey<P>, value: T, startedAt: Long = key.mintedAt): Boolean =
        writeOutcome(key, value, startedAt) == RaftOfflineWriteOutcome.Written

    internal suspend fun writeOutcome(key: RaftOfflineResourceKey<P>, value: T, startedAt: Long): RaftOfflineWriteOutcome {
        val rowId = rowId(key) ?: return RaftOfflineWriteOutcome.Failed
        return cache.write(rowId, encode(value), key.mintedAt, startedAt, spec.maxEntriesPerAccount)
    }

    /**
     * Write-through for a confirmed mutation: [transform] the stored value (null removes it), as one
     * read-modify-write under the cache's lock; an in-flight fetch that started before it cannot overwrite it.
     * Call only after the server confirmed the change; a failed mutation leaves the stored value alone. No
     * stored value: no-op (false).
     */
    suspend fun update(key: RaftOfflineResourceKey<P>, transform: (T) -> T?): Boolean {
        val rowId = rowId(key) ?: return false
        return cache.update(rowId, key.mintedAt, spec.maxAgeMillis, spec.maxEntriesPerAccount) { encoded ->
            val next = decodeOrNull(encoded)?.let(transform)
            next?.let(::encode)?.let { RaftOfflineChange.Write(it) } ?: RaftOfflineChange.Remove
        }
    }

    /** Drops the stored value (a confirmed removal: an in-flight fetch that started before cannot restore it). */
    suspend fun remove(key: RaftOfflineResourceKey<P>) {
        val rowId = rowId(key) ?: return
        cache.delete(rowId, confirmed = true)
    }

    /** A Store5 store whose fetcher is [fetch] and whose source of truth is this resource's rows. */
    fun store(fetch: suspend (RaftOfflineResourceKey<P>) -> DataResult<T>): RaftOfflineResourceStore<P, T> =
        RaftOfflineResourceStore(this, cache.storeScope, fetch)

    internal fun fetchStarted(): Long = cache.tick()

    private fun decodeOrNull(encoded: String): T? =
        runCatching { ResourceJson.decodeFromString(spec.serializer, encoded) }.getOrNull()

    /** The stored encoding of [value], or null when it is larger than the resource's cap. */
    private fun encode(value: T): String? =
        ResourceJson.encodeToString(spec.serializer, spec.fit(value))
            .takeIf { it.encodeToByteArray().size <= spec.maxBytesPerEntry }

    private fun rowId(key: RaftOfflineResourceKey<P>): String? =
        RaftOfflineRowId.of(
            resource = "${spec.name}:v${spec.version}",
            environmentId = key.environmentId,
            accountId = key.accountId,
            serverId = key.serverId,
            paramsId = spec.paramsId(key.params),
        )

    private companion object {
        val ResourceJson = Json { ignoreUnknownKeys = true }
    }
}

/** What [RaftOfflineResourceStore.revalidate] emits: at most one [StandIn], then exactly one answer. */
internal sealed interface RaftOfflineEmission<out T> {
    /** The stored value, before the network answered: show it marked as refreshing. */
    data class StandIn<T>(val value: T) : RaftOfflineEmission<T>

    /** The network answer (already stored). */
    data class Fresh<T>(val value: T) : RaftOfflineEmission<T>

    /**
     * The request failed ([result] is the Error / Empty / NoPermission it returned). The stored value stays
     * (and stays shown) except on NoPermission: lost access drops it.
     */
    data class Failed<T>(val result: DataResult<T>) : RaftOfflineEmission<T>
}

internal class RaftOfflineResourceStore<P : Any, T : Any>(
    private val resource: RaftOfflineResource<P, T>,
    scope: CoroutineScope,
    private val fetch: suspend (RaftOfflineResourceKey<P>) -> DataResult<T>,
) {
    private val lock = SynchronizedObject()

    // The network value of each key whose stored echo is pending: answers [revalidate] even when the value
    // could not be stored (fenced / too large / write failure).
    private val answers = mutableMapOf<RaftOfflineResourceKey<P>, T>()

    // When the request behind each key's pending answer started: the writer's fence against a confirmed
    // mutation that happened meanwhile. Store5 shares one request per key, so one entry per key suffices.
    private val fetchStarts = mutableMapOf<RaftOfflineResourceKey<P>, Long>()

    // Keys whose last network answer was older than a confirmed mutation of the stored value.
    private val superseded = mutableSetOf<RaftOfflineResourceKey<P>>()

    val store: Store<RaftOfflineResourceKey<P>, T> =
        StoreBuilder.from(
            fetcher =
                Fetcher.ofResult<RaftOfflineResourceKey<P>, T> { key ->
                    val startedAt = resource.fetchStarted()
                    try {
                        when (val result = fetch(key)) {
                            is DataResult.Content -> {
                                synchronized(lock) {
                                    answers[key] = result.data
                                    fetchStarts[key] = startedAt
                                }
                                FetcherResult.Data(result.data)
                            }
                            else -> FetcherResult.Error.Custom(FetchFailure(result))
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        FetcherResult.Error.Exception(error)
                    }
                },
            sourceOfTruth =
                SourceOfTruth.of<RaftOfflineResourceKey<P>, T, T>(
                    nonFlowReader = { key -> resource.read(key) },
                    writer = { key, value ->
                        val startedAt = synchronized(lock) { fetchStarts.remove(key) } ?: key.mintedAt
                        val outcome = resource.writeOutcome(key, value, startedAt)
                        synchronized(lock) {
                            if (outcome == RaftOfflineWriteOutcome.Superseded) superseded += key else superseded -= key
                        }
                    },
                    delete = { key -> resource.remove(key) },
                ),
        ).scope(scope).disableCache().build()

    /**
     * Stale-while-revalidate: the stored value (if any) as [RaftOfflineEmission.StandIn], then the network
     * answer as [RaftOfflineEmission.Fresh] (stored first; the stored value instead when a confirmed mutation
     * superseded the answer while it was in flight, or a second answer when nothing is stored then) or
     * [RaftOfflineEmission.Failed]; then completes.
     * A fetch that threw rethrows to the collector.
     */
    fun revalidate(key: RaftOfflineResourceKey<P>): Flow<RaftOfflineEmission<T>> =
        flow {
            var superseded = false
            revalidateOnce(key, StoreReadRequest.cached(key, refresh = true)).collect { emission ->
                if (emission == null) superseded = true else emit(emission)
            }
            if (!superseded) return@flow
            // The answer predates a confirmed mutation and nothing newer is stored to show instead: ask once
            // more (that request starts after the mutation).
            var again = false
            revalidateOnce(key, StoreReadRequest.fresh(key)).collect { emission ->
                if (emission == null) again = true else if (emission !is RaftOfflineEmission.StandIn) emit(emission)
            }
            if (again) {
                emit(
                    RaftOfflineEmission.Failed(
                        DataResult.Error(ScreenError(code = "offline_resource_superseded", retryable = true))
                    )
                )
            }
        }

    /** One Store5 round of [revalidate]; null: the network answer was superseded and nothing is stored. */
    private fun revalidateOnce(
        key: RaftOfflineResourceKey<P>,
        request: StoreReadRequest<RaftOfflineResourceKey<P>>,
    ): Flow<RaftOfflineEmission<T>?> =
        store.stream(request).transformWhile { response ->
            when (response) {
                is StoreReadResponse.Data -> {
                    if (response.origin is StoreReadResponseOrigin.Fetcher) {
                        emit(freshEmission(key, takeAnswer(key) ?: response.value))
                        false
                    } else {
                        @Suppress("SENSELESS_COMPARISON")
                        if (response.value != null) emit(RaftOfflineEmission.StandIn(response.value))
                        true
                    }
                }
                is StoreReadResponse.Error.Custom<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val result = (response.error as? FetchFailure<T>)?.result
                        ?: DataResult.Error(ScreenError(code = "offline_resource_failed", retryable = true))
                    if (result is DataResult.NoPermission) resource.remove(key)
                    emit(RaftOfflineEmission.Failed(result))
                    false
                }
                is StoreReadResponse.Error.Exception -> {
                    val error = response.error
                    if (error is SourceOfTruth.WriteException) {
                        // The network answered but the row could not be written: the answer still stands.
                        val answer = takeAnswer(key)
                        emit(
                            if (answer != null) {
                                RaftOfflineEmission.Fresh(answer)
                            } else {
                                RaftOfflineEmission.Failed(
                                    DataResult.Error(ScreenError(code = "offline_resource_write_failed", retryable = true))
                                )
                            }
                        )
                        false
                    } else if (error is SourceOfTruth.ReadException) {
                        // Nothing readable on disk: wait for the network.
                        true
                    } else {
                        throw error
                    }
                }
                is StoreReadResponse.Error.Message -> {
                    emit(RaftOfflineEmission.Failed(DataResult.Error(ScreenError(code = response.message, retryable = true))))
                    false
                }
                is StoreReadResponse.NoNewData -> {
                    if (response.origin is StoreReadResponseOrigin.Fetcher) {
                        emit(
                            RaftOfflineEmission.Failed(
                                DataResult.Error(ScreenError(code = "offline_resource_no_data", retryable = true))
                            )
                        )
                        false
                    } else {
                        true
                    }
                }
                is StoreReadResponse.Loading,
                StoreReadResponse.Initial -> true
            }
        }

    private fun takeAnswer(key: RaftOfflineResourceKey<P>): T? = synchronized(lock) { answers.remove(key) }

    /**
     * The answer to show: the network's, unless a confirmed mutation made the stored value newer while the
     * request was in flight: then the stored value (so the UI does not resurrect what the mutation changed),
     * or null when nothing is stored (the caller asks again).
     */
    private suspend fun freshEmission(key: RaftOfflineResourceKey<P>, answer: T): RaftOfflineEmission<T>? {
        val stale = synchronized(lock) { key in superseded }
        if (!stale) return RaftOfflineEmission.Fresh(answer)
        return resource.read(key)?.let { RaftOfflineEmission.Fresh(it) }
    }

    private class FetchFailure<T>(val result: DataResult<T>)
}

/** Raw rows of the offline resources. Production uses one `localSnapshotEntity` scope; tests use a map. */
internal interface RaftOfflineResourceRows {
    suspend fun read(id: String): RaftOfflineResourceRow?

    /** Upserts [id], then deletes the ids [evict] picks among every row, in one transaction. */
    suspend fun writeAndEvict(id: String, value: String, updatedAtMs: Long, evict: (List<RaftOfflineResourceRowMeta>) -> List<String>)

    suspend fun delete(ids: List<String>)

    suspend fun deleteAll()

    suspend fun ids(): List<String>
}

internal data class RaftOfflineResourceRow(val value: String, val updatedAtMs: Long)

internal data class RaftOfflineResourceRowMeta(val id: String, val updatedAtMs: Long)

internal const val RaftOfflineResourceSnapshotScope = "raft_offline_resource_v1"

/** [RaftOfflineResourceRows] on `localSnapshotEntity` ([RaftOfflineResourceSnapshotScope]). */
internal class SqlDelightRaftOfflineResourceRows(
    private val database: RaftSqlDatabase,
    private val snapshotScope: String = RaftOfflineResourceSnapshotScope,
) : RaftOfflineResourceRows {
    override suspend fun read(id: String): RaftOfflineResourceRow? =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshotWithTime(snapshotScope, id).executeAsOneOrNull()
                ?.let { RaftOfflineResourceRow(it.value_, it.updatedAtMs) }
        }

    override suspend fun writeAndEvict(
        id: String,
        value: String,
        updatedAtMs: Long,
        evict: (List<RaftOfflineResourceRowMeta>) -> List<String>,
    ) {
        database.transaction {
            queries.upsertSnapshot(snapshotScope, id, value, updatedAtMs)
            val metas =
                queries.selectSnapshotRowTimes(snapshotScope).executeAsList()
                    .map { RaftOfflineResourceRowMeta(it.id, it.updatedAtMs) }
            evict(metas).forEach { victim -> queries.deleteSnapshot(snapshotScope, victim) }
        }
    }

    override suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        database.transaction {
            ids.forEach { id -> queries.deleteSnapshot(snapshotScope, id) }
        }
    }

    override suspend fun deleteAll() {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.deleteSnapshotScope(snapshotScope)
        }
    }

    override suspend fun ids(): List<String> =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshotIds(snapshotScope).executeAsList()
        }
}

/** Row ids: resource, environment, account, server, params joined by a unit separator. */
internal object RaftOfflineRowId {
    private const val Separator = "\u001f"

    data class Parts(
        val resource: String,
        val environmentId: String,
        val accountId: String,
        val serverId: String,
        val paramsId: String,
    ) {
        /** Rows sharing a resource + environment + account (the per-account cap's group). */
        val accountGroup: String
            get() = listOf(resource, environmentId, accountId).joinToString(Separator)
    }

    fun of(resource: String, environmentId: String, accountId: String, serverId: String, paramsId: String): String? {
        val parts = listOf(resource, environmentId, accountId, serverId)
        if (parts.any { it.isEmpty() || Separator in it } || Separator in paramsId) return null
        return (parts + paramsId).joinToString(Separator)
    }

    fun parts(id: String): Parts? {
        val split = id.split(Separator)
        if (split.size != 5) return null
        return Parts(split[0], split[1], split[2], split[3], split[4])
    }
}
