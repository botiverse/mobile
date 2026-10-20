package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Write confinement for process-wide stores.
 *
 * While Kuikly and Compose Multiplatform pages coexist, two UI threads (Kuikly's context thread and
 * the platform main thread) can reach the same process-wide stores. Those stores now run their work
 * on one serial [dispatcher] (a `limitedParallelism(1)` view of `Dispatchers.Default`): every
 * coroutine a provider-owned store launches executes one task at a time, so state writes made from
 * those coroutines never race each other, whichever UI issued the intent.
 *
 * The dispatcher is for store-state mutation only. Work that is not a state write (network,
 * realtime decode, disk/SQLite I/O, prewarm, reconcile loops) runs on parallel scopes and hops onto
 * [dispatcher] just for the final write; a blocking call that does reach the store dispatcher (e.g.
 * a SQLite transaction) is moved off it with [runOffStoreDispatcher] or reported by
 * [checkNotOnStoreDispatcher].
 *
 * Stores declare their state with [raftStoreState]; in debug a [violationHandler] is installed
 * (Kuikly pages: the existing `verifyThread` switch; unit tests: a recorder/throw), and any write to
 * such a state from outside [dispatcher], or any blocking call on it, is reported with the store /
 * operation name and thread.
 */
@RaftCoreInternalApi
object RaftStoreThreading {
    val dispatcher: CoroutineDispatcher = raftStoreConfinedDispatcher(Dispatchers.Default)

    /**
     * A supervisor scope whose coroutines all run on [dispatcher]. Only for stores whose coroutines
     * mutate store state; everything else uses a parallel scope.
     */
    fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** True while the current thread is executing a [dispatcher] task. */
    val isOnStoreDispatcher: Boolean
        get() = RaftStoreConfinement.isConfined()

    /**
     * Debug-only write guard. Null (the default, and always in release) disables checking entirely,
     * so production pays one null check per write. Read from any thread; set only through
     * [installViolationHandler].
     */
    @Volatile
    var violationHandler: ((RaftStoreThreadingViolation) -> Unit)? = null
        private set

    /** Debug wiring only (Kuikly `verifyThread`, unit tests); pass null to disable checking. */
    fun installViolationHandler(handler: ((RaftStoreThreadingViolation) -> Unit)?) {
        violationHandler = handler
    }

    internal fun checkWrite(store: String) {
        val handler = violationHandler ?: return
        if (!RaftStoreConfinement.isConfined()) {
            handler(
                RaftStoreThreadingViolation(
                    kind = RaftStoreThreadingViolation.Kind.WriteOffStoreDispatcher,
                    subject = store,
                    thread = RaftStoreConfinement.currentThreadLabel(),
                )
            )
        }
    }

    /**
     * Debug assertion for blocking work (`runBlocking`-backed SQLite transactions, synchronous disk
     * I/O): reports [operation] when it runs on the store dispatcher, where it would stall every
     * store write in the process.
     */
    fun checkNotOnStoreDispatcher(operation: String) {
        val handler = violationHandler ?: return
        if (RaftStoreConfinement.isConfined()) {
            handler(
                RaftStoreThreadingViolation(
                    kind = RaftStoreThreadingViolation.Kind.BlockingCallOnStoreDispatcher,
                    subject = operation,
                    thread = RaftStoreConfinement.currentThreadLabel(),
                )
            )
        }
    }

    /**
     * Runs [block] off the store dispatcher: on the parallel default pool when called from a store
     * task, in place otherwise (so callers already off it keep their thread, e.g. an enclosing
     * SQLite transaction's connection-pinned thread).
     */
    suspend fun <T> runOffStoreDispatcher(block: suspend () -> T): T =
        if (RaftStoreConfinement.isConfined()) withContext(Dispatchers.Default) { block() } else block()
}

@RaftCoreInternalApi
data class RaftStoreThreadingViolation(
    val kind: Kind,
    /** Store name for [Kind.WriteOffStoreDispatcher], operation name for [Kind.BlockingCallOnStoreDispatcher]. */
    val subject: String,
    val thread: String,
) {
    enum class Kind {
        WriteOffStoreDispatcher,
        BlockingCallOnStoreDispatcher,
    }

    override fun toString(): String =
        when (kind) {
            Kind.WriteOffStoreDispatcher -> "store_write_off_dispatcher store=$subject thread=$thread"
            Kind.BlockingCallOnStoreDispatcher -> "blocking_call_on_store_dispatcher operation=$subject thread=$thread"
        }
}

/**
 * State holder for a store whose writes are confined to [RaftStoreThreading.dispatcher]. Reads are
 * unchanged; `value =`, `update {}` / `compareAndSet` and `emit` are checked in debug.
 */
@RaftCoreInternalApi
fun <T> raftStoreState(initial: T, store: String): MutableStateFlow<T> =
    RaftStoreStateFlow(MutableStateFlow(initial), store)

private class RaftStoreStateFlow<T>(
    private val delegate: MutableStateFlow<T>,
    private val store: String,
) : MutableStateFlow<T> {
    override var value: T
        get() = delegate.value
        set(value) {
            RaftStoreThreading.checkWrite(store)
            delegate.value = value
        }

    override fun compareAndSet(expect: T, update: T): Boolean {
        RaftStoreThreading.checkWrite(store)
        return delegate.compareAndSet(expect, update)
    }

    override val replayCache: List<T> get() = delegate.replayCache
    override val subscriptionCount: StateFlow<Int> get() = delegate.subscriptionCount

    override suspend fun collect(collector: FlowCollector<T>): Nothing = delegate.collect(collector)

    override suspend fun emit(value: T) {
        RaftStoreThreading.checkWrite(store)
        delegate.emit(value)
    }

    override fun tryEmit(value: T): Boolean {
        RaftStoreThreading.checkWrite(store)
        return delegate.tryEmit(value)
    }

    @ExperimentalCoroutinesApi
    override fun resetReplayCache() = delegate.resetReplayCache()
}

/**
 * A serial (`limitedParallelism(1)`) view of [delegate] whose tasks are marked as confined. Internal
 * so tests can drive it with a manual delegate instead of real threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun raftStoreConfinedDispatcher(delegate: CoroutineDispatcher): CoroutineDispatcher =
    RaftStoreConfinedDispatcher(delegate.limitedParallelism(1))

/** Marks every task it runs so [RaftStoreThreading.checkWrite] can tell confined writes apart. */
private class RaftStoreConfinedDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, Runnable {
            RaftStoreConfinement.enter()
            try {
                block.run()
            } finally {
                RaftStoreConfinement.exit()
            }
        })
    }

    override fun toString(): String = "SlockStoreDispatcher"
}

/**
 * Per-thread confinement depth (JVM `ThreadLocal`, Kotlin/Native `@ThreadLocal`). Internal: only
 * [RaftStoreConfinedDispatcher] may enter/exit, so no other code can mark itself as confined.
 */
internal expect object RaftStoreConfinement {
    fun enter()

    fun exit()

    fun isConfined(): Boolean

    fun currentThreadLabel(): String
}
