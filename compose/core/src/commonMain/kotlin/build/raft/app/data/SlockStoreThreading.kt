package build.raft.app.data

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

/**
 * Write confinement for process-wide stores.
 *
 * While Kuikly and Compose Multiplatform pages coexist, two UI threads (Kuikly's context thread and
 * the platform main thread) can reach the same process-wide stores. Those stores now run their work
 * on one serial [dispatcher] (a `limitedParallelism(1)` view of `Dispatchers.Default`): every
 * coroutine a provider-owned store launches executes one task at a time, so state writes made from
 * those coroutines never race each other, whichever UI issued the intent.
 *
 * Stores declare their state with [slockStoreState]; in debug a [violationHandler] is installed
 * (Kuikly pages: the existing `verifyThread` switch; unit tests: a recorder/throw), and any write to
 * such a state from outside [dispatcher] is reported with the store name and thread.
 */
object SlockStoreThreading {
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = SlockStoreConfinedDispatcher(Dispatchers.Default.limitedParallelism(1))

    /** A supervisor scope whose coroutines all run on [dispatcher]. */
    fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** True while the current thread is executing a [dispatcher] task. */
    val isOnStoreDispatcher: Boolean
        get() = SlockStoreConfinement.isConfined()

    /**
     * Debug-only write guard. Null (the default, and always in release) disables checking entirely,
     * so production pays one null check per write.
     */
    var violationHandler: ((SlockStoreWriteViolation) -> Unit)? = null

    fun checkWrite(store: String) {
        val handler = violationHandler ?: return
        if (!SlockStoreConfinement.isConfined()) {
            handler(SlockStoreWriteViolation(store = store, thread = SlockStoreConfinement.currentThreadLabel()))
        }
    }
}

data class SlockStoreWriteViolation(
    val store: String,
    val thread: String,
) {
    override fun toString(): String = "store_write_off_dispatcher store=$store thread=$thread"
}

/**
 * State holder for a store whose writes are confined to [SlockStoreThreading.dispatcher]. Reads are
 * unchanged; `value =`, `update {}` / `compareAndSet` and `emit` are checked in debug.
 */
fun <T> slockStoreState(initial: T, store: String): MutableStateFlow<T> =
    SlockStoreStateFlow(MutableStateFlow(initial), store)

private class SlockStoreStateFlow<T>(
    private val delegate: MutableStateFlow<T>,
    private val store: String,
) : MutableStateFlow<T> {
    override var value: T
        get() = delegate.value
        set(value) {
            SlockStoreThreading.checkWrite(store)
            delegate.value = value
        }

    override fun compareAndSet(expect: T, update: T): Boolean {
        SlockStoreThreading.checkWrite(store)
        return delegate.compareAndSet(expect, update)
    }

    override val replayCache: List<T> get() = delegate.replayCache
    override val subscriptionCount: StateFlow<Int> get() = delegate.subscriptionCount

    override suspend fun collect(collector: FlowCollector<T>): Nothing = delegate.collect(collector)

    override suspend fun emit(value: T) {
        SlockStoreThreading.checkWrite(store)
        delegate.emit(value)
    }

    override fun tryEmit(value: T): Boolean {
        SlockStoreThreading.checkWrite(store)
        return delegate.tryEmit(value)
    }

    @ExperimentalCoroutinesApi
    override fun resetReplayCache() = delegate.resetReplayCache()
}

/** Marks every task it runs so [SlockStoreThreading.checkWrite] can tell confined writes apart. */
private class SlockStoreConfinedDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, Runnable {
            SlockStoreConfinement.enter()
            try {
                block.run()
            } finally {
                SlockStoreConfinement.exit()
            }
        })
    }

    override fun toString(): String = "SlockStoreDispatcher"
}

/** Per-thread confinement depth (JVM `ThreadLocal`, Kotlin/Native `@ThreadLocal`). */
expect object SlockStoreConfinement {
    fun enter()

    fun exit()

    fun isConfined(): Boolean

    fun currentThreadLabel(): String
}
