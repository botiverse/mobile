package build.raft.app.base

import com.tencent.kuikly.core.timer.setTimeout
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * task #25 stopgap: marshal BridgeModule log emission onto the Kuikly context
 * thread.
 *
 * Kuikly's contract is that every Kotlin-side bridge call runs on the context
 * thread, and the fork enforces it with a C `assert()` deep in
 * KuiklyRenderUIScheduler (live in release builds — the TestFlight SIGABRTs).
 * Shared code has NO primitive to detect the context thread and NO safe
 * off-thread delivery: even `setTimeout` itself crosses the bridge, so
 * "detect and hop" cannot be built at a call site (the circular trap in
 * #iOS适配:7f5e0b6f). Diagnostics lambdas, however, legitimately fire from
 * worker coroutines (e.g. the parse dispatchers), so the sink must absorb the
 * threading instead of every caller knowing the contract.
 *
 * Design (armed flush): [enqueue] is thread-safe and never touches the bridge.
 * A flush loop is ARMED from [install], which is only called from
 * [BasePager.created] — Kuikly page lifecycle, guaranteed context thread — so
 * the `setTimeout` chain (and therefore every real emission in [flushNow])
 * always runs on the context thread. Multiple live pagers may each run a loop;
 * the drain is an atomic swap so every entry is emitted exactly once.
 *
 * Costs, accepted for diagnostics-grade logs: up to [FLUSH_INTERVAL_MS] delay,
 * entries still queued at crash time are lost, and entries beyond
 * [MAX_PENDING] drop oldest-first. The real fix is the fork-side boundary
 * marshal (task #26); remove this pump when that lands.
 */
internal object SlockBridgeLogPump {

    internal data class Entry(val level: BridgeLogLevel, val content: String)

    private const val FLUSH_INTERVAL_MS = 200
    private const val MAX_PENDING = 512

    private val pending = atomic(listOf<Entry>())

    /** Thread-safe; callable from any thread. Never crosses the bridge. */
    fun enqueue(level: BridgeLogLevel, content: String) {
        pending.update { current ->
            val appended = current + Entry(level, content)
            if (appended.size > MAX_PENDING) {
                appended.subList(appended.size - MAX_PENDING, appended.size).toList()
            } else {
                appended
            }
        }
    }

    /**
     * Arm this pager's flush loop. MUST be called on the context thread
     * (BasePager.created). The loop dies with the pager (its timer callbacks
     * are pager-scoped), so no explicit teardown is needed.
     */
    fun install(pagerId: String) {
        scheduleFlush(pagerId)
    }

    private fun scheduleFlush(pagerId: String) {
        setTimeout(pagerId, FLUSH_INTERVAL_MS) {
            flushNow(pagerId)
            scheduleFlush(pagerId)
        }
    }

    /** Runs on the context thread by construction (setTimeout callback). */
    private fun flushNow(pagerId: String) {
        val drained = pending.getAndSet(emptyList())
        if (drained.isEmpty()) {
            return
        }
        val bridge = runCatching { Utils.bridgeModule(pagerId) }.getOrNull() ?: run {
            // Pager torn down between arm and fire: requeue for a surviving pump.
            pending.update { current -> drained + current }
            return
        }
        drained.forEach { entry ->
            bridge.logNow(entry.level, entry.content)
        }
    }

    /** Test hook: drain without a bridge (returns entries in emission order). */
    internal fun drainForTest(): List<Entry> = pending.getAndSet(emptyList())
}
