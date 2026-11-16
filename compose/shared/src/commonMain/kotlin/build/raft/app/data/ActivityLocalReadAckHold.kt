package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Web `LOCAL_READ_ACK_WINDOW_MS`: how long a `local_read` lowering waits for this client's read ack. */
internal const val ActivityLocalReadAckWindowMillis = 5_000L

/**
 * `inbox_row_position_v1`, web `holdLocalRead` / `noteLocalReadAck` (slock #15197,
 * packages/web/src/store/inboxListRowLowered.ts): a `local_read` lowering is held per scope for
 * [windowMillis]. This client's read ack for the scope settles it ([noteAck]): corrected when the
 * ack's read covers the answer's latest activity, lowered otherwise. With no usable ack the window
 * sends it as lowered. Exactly one event per row; its timestamp stays the lowering time.
 *
 * Unlike web (which drops held rows when the tab closes), [flush] sends every held row at once as a
 * lowering when the app goes to the background, the store switches server or the session ends.
 * A process killed while rows are held (or before the trace sender's batch goes out) loses them.
 *
 * [sink] runs outside this hold's lock and never under the store's realtime lock from here: it must
 * only queue (as [RaftClientTraceEvents.emitListRowLowered] does).
 */
internal class ActivityLocalReadAckHold(
    private val scope: CoroutineScope,
    /** Monotonic clock for `ack_delay_ms`; injectable for virtual time. */
    private val nowMillis: () -> Long,
    /** Where the window's timer runs (the store's scope by default); injectable for tests. */
    private val timerContext: CoroutineContext = EmptyCoroutineContext,
    private val windowMillis: Long = ActivityLocalReadAckWindowMillis,
    private val log: (String) -> Unit = {},
    private val sink: (serverId: String, lowered: ActivityListRowLowered) -> Unit,
) {
    private class Held(val serverId: String, val row: ActivityListRowLowered, val heldAtMillis: Long) {
        var timer: Job? = null
    }

    private val lock = SynchronizedObject()
    private val held = mutableMapOf<String, MutableList<Held>>()

    /** Rows held right now (all scopes). */
    internal val heldCount: Int get() = synchronized(lock) { held.values.sumOf { it.size } }

    /** Holds a `local_read` [row] of [serverId] (its scope id must be set) until its ack or the window. */
    fun hold(serverId: String, row: ActivityListRowLowered) {
        val scopeId = row.scopeId ?: return send(serverId, row)
        val entry = Held(serverId, row, nowMillis())
        synchronized(lock) { held.getOrPut(scopeId) { mutableListOf() } += entry }
        entry.timer =
            scope.launch(timerContext) {
                delay(windowMillis)
                val expired =
                    synchronized(lock) {
                        val list = held[scopeId] ?: return@synchronized false
                        val removed = list.remove(entry)
                        if (list.isEmpty()) held.remove(scopeId)
                        removed
                    }
                if (expired) send(serverId, row.copy(heldEnd = ActivityListRowHeldEnd.Window))
            }
    }

    /**
     * This client's read of [scopeId] was acknowledged with [maxReadSeq]. Settles the scope's held
     * rows; an ack without a usable seq is ignored (the window decides).
     */
    fun noteAck(scopeId: String, maxReadSeq: Long?) {
        val readSeq = parseListRowSeq(maxReadSeq) ?: return
        val settled = synchronized(lock) { held.remove(scopeId) } ?: return
        val now = nowMillis()
        for (entry in settled) {
            entry.timer?.cancel()
            send(
                entry.serverId,
                entry.row.copy(
                    ackReadSeq = readSeq,
                    ackDelayMs = maxOf(0L, now - entry.heldAtMillis),
                    heldEnd = ActivityListRowHeldEnd.Ack
                )
            )
        }
    }

    /** Sends every held row now, as a lowering with no ack ([end] says why). */
    fun flush(end: ActivityListRowHeldEnd) {
        val all =
            synchronized(lock) {
                val entries = held.values.flatten()
                held.clear()
                entries
            }
        for (entry in all) {
            entry.timer?.cancel()
            send(entry.serverId, entry.row.copy(heldEnd = end))
        }
    }

    private fun send(serverId: String, row: ActivityListRowLowered) {
        log(
            "activity_list_row_${if (row.corrected) "corrected" else "lowered"} step=${row.step.wireValue} " +
                "before=${row.before} served=${row.served} after=${row.after} " +
                "held_end=${row.heldEnd?.wireValue ?: "-"} ack_read_seq=${row.ackReadSeq ?: "-"} ack_delay_ms=${row.ackDelayMs ?: "-"}"
        )
        sink(serverId, row)
    }
}
