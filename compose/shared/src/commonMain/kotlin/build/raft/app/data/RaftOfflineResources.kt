package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.concurrent.Volatile

/**
 * Offline-first resources (artin: everything fetched is stored locally; persistence audit item 4).
 *
 * One process-wide [RaftOfflineResourceCache] owns every resource's durable rows, in ONE
 * `localSnapshotEntity` scope, so:
 * - sign-out / account switch wipes every resource with one call ([wipeAll], registered once in the
 *   provider's auth-terminal cleanup); a new resource needs no cleanup wiring of its own, and rows of a
 *   resource this process never instantiated go too;
 * - lost access to a server wipes that server's rows of every resource ([wipeServer]).
 *
 * A resource ([resource]) is keyed by environment + account + server + resource name + params
 * ([RaftOfflineResourceKey]); values are encoded with the resource's kotlinx-serialization codec. Each
 * resource has its own caps (entries per account, bytes per entry, optional max age).
 *
 * Reads go through MobileNativeFoundation Store5 ([RaftOfflineResource.store]): the rows are Store5's
 * source of truth and the consumer's request is its fetcher; [RaftOfflineResourceStore.revalidate] is the
 * stale-while-revalidate read (stored value first as a stand-in, then exactly one network answer). Store5's
 * memory cache is off: the rows are the single source of truth.
 *
 * Writes are fenced by a generation: [wipeAll] / [wipeServer] advance it, and a key minted before
 * (an in-flight request of the signed-out account) can no longer write.
 */
internal class RaftOfflineResourceCache(
    private val rows: RaftOfflineResourceRows,
    private val environmentId: () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile
    private var generationValue = 0L

    internal val generation: Long
        get() = generationValue

    internal val storeScope: CoroutineScope
        get() = scope

    fun <P : Any, T : Any> resource(spec: RaftOfflineResourceSpec<P, T>): RaftOfflineResource<P, T> =
        RaftOfflineResource(this, spec)

    /** Sign-out / account switch: every row of every resource; in-flight writes are fenced. */
    suspend fun wipeAll() {
        generationValue += 1L
        mutex.withLock { runLogged("wipe_all") { rows.deleteAll() } }
    }

    /** Synchronous fence, asynchronous delete: for the non-suspending auth-terminal cleanup. */
    fun wipeAllAsync() {
        generationValue += 1L
        scope.launch { mutex.withLock { runLogged("wipe_all") { rows.deleteAll() } } }
    }

    /** Lost access to [serverId] ([accountId] null = every account): that server's rows of every resource. */
    suspend fun wipeServer(serverId: String, accountId: String? = null) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val account = accountId?.trim()?.takeIf { it.isNotEmpty() }
        generationValue += 1L
        mutex.withLock {
            runLogged("wipe_server") {
                val victims =
                    rows.ids().filter { id ->
                        val parts = RaftOfflineRowId.parts(id) ?: return@filter false
                        parts.serverId == server && (account == null || parts.accountId == account)
                    }
                rows.delete(victims)
            }
        }
    }

    fun wipeServerAsync(serverId: String, accountId: String? = null) {
        scope.launch { wipeServer(serverId, accountId) }
    }

    internal fun mintKey(accountId: String, serverId: String): Triple<String, String, String>? {
        val environment = environmentId()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val account = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        return Triple(environment, account, server)
    }

    internal suspend fun read(rowId: String, maxAgeMillis: Long?): String? {
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
            mutex.withLock { runLogged("expire") { rows.delete(listOf(rowId)) } }
            return null
        }
        return row.value
    }

    /**
     * Writes [encoded] for [rowId] unless the key's [generation] is stale; drops the oldest rows of the same
     * resource + environment + account past [maxEntriesPerAccount] in the same transaction.
     */
    internal suspend fun write(rowId: String, encoded: String, generation: Long, maxEntriesPerAccount: Int): Boolean =
        mutex.withLock {
            if (generation != generationValue) {
                logger("offline_resource_write result=fenced")
                return@withLock false
            }
            val group = RaftOfflineRowId.parts(rowId)?.accountGroup ?: return@withLock false
            val cap = maxEntriesPerAccount.coerceAtLeast(1)
            try {
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

    internal suspend fun delete(rowId: String) {
        mutex.withLock { runLogged("delete") { rows.delete(listOf(rowId)) } }
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

/** Environment + account + server + params of one stored value; minted by [RaftOfflineResource.key]. */
internal data class RaftOfflineResourceKey<P : Any>(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val params: P,
    val generation: Long,
)

internal class RaftOfflineResource<P : Any, T : Any>(
    private val cache: RaftOfflineResourceCache,
    private val spec: RaftOfflineResourceSpec<P, T>,
) {
    /** The key of ([accountId], [serverId], [params]) under the current environment and generation. */
    fun key(accountId: String, serverId: String, params: P): RaftOfflineResourceKey<P>? {
        val (environment, account, server) = cache.mintKey(accountId, serverId) ?: return null
        return RaftOfflineResourceKey(environment, account, server, params, cache.generation)
    }

    suspend fun read(key: RaftOfflineResourceKey<P>): T? {
        val rowId = rowId(key) ?: return null
        val encoded = cache.read(rowId, spec.maxAgeMillis) ?: return null
        val decoded = runCatching { ResourceJson.decodeFromString(spec.serializer, encoded) }.getOrNull()
        if (decoded == null) cache.delete(rowId)
        return decoded
    }

    /** Stores [value] (after [RaftOfflineResourceSpec.fit]); false when fenced, too large or failed. */
    suspend fun write(key: RaftOfflineResourceKey<P>, value: T): Boolean {
        val rowId = rowId(key) ?: return false
        val encoded = ResourceJson.encodeToString(spec.serializer, spec.fit(value))
        if (encoded.encodeToByteArray().size > spec.maxBytesPerEntry) {
            cache.delete(rowId)
            return false
        }
        return cache.write(rowId, encoded, key.generation, spec.maxEntriesPerAccount)
    }

    /**
     * Write-through for a confirmed mutation: [transform] the stored value (null removes it). Call only after
     * the server confirmed the change; a failed mutation leaves the stored value alone. No stored value: no-op.
     */
    suspend fun update(key: RaftOfflineResourceKey<P>, transform: (T) -> T?): Boolean {
        val current = read(key) ?: return false
        val next = transform(current)
        if (next == null) {
            remove(key)
            return true
        }
        return write(key, next)
    }

    suspend fun remove(key: RaftOfflineResourceKey<P>) {
        val rowId = rowId(key) ?: return
        cache.delete(rowId)
    }

    /** A Store5 store whose fetcher is [fetch] and whose source of truth is this resource's rows. */
    fun store(fetch: suspend (RaftOfflineResourceKey<P>) -> DataResult<T>): RaftOfflineResourceStore<P, T> =
        RaftOfflineResourceStore(this, cache.storeScope, fetch)

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

    val store: Store<RaftOfflineResourceKey<P>, T> =
        StoreBuilder.from(
            fetcher =
                Fetcher.ofResult<RaftOfflineResourceKey<P>, T> { key ->
                    try {
                        when (val result = fetch(key)) {
                            is DataResult.Content -> {
                                synchronized(lock) { answers[key] = result.data }
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
                    writer = { key, value -> resource.write(key, value) },
                    delete = { key -> resource.remove(key) },
                ),
        ).scope(scope).disableCache().build()

    /**
     * Stale-while-revalidate: the stored value (if any) as [RaftOfflineEmission.StandIn], then the network
     * answer as [RaftOfflineEmission.Fresh] (stored first) or [RaftOfflineEmission.Failed]; then completes.
     * A fetch that threw rethrows to the collector.
     */
    fun revalidate(key: RaftOfflineResourceKey<P>): Flow<RaftOfflineEmission<T>> =
        store.stream(StoreReadRequest.cached(key, refresh = true)).transformWhile { response ->
            when (response) {
                is StoreReadResponse.Data -> {
                    if (response.origin is StoreReadResponseOrigin.Fetcher) {
                        emit(RaftOfflineEmission.Fresh(takeAnswer(key) ?: response.value))
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
