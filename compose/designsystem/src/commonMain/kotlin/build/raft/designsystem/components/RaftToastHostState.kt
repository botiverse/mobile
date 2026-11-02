package build.raft.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/** raft-ui `ToastProvider` `timeout`: how long a toast stays, 5s. */
const val RaftToastTimeoutMillis: Long = 5_000

/** raft-ui `actionTimeout`: how long a toast with an action, or an `error`, stays, 8s. */
const val RaftToastActionTimeoutMillis: Long = 8_000

/** raft-ui `ToastProvider` `limit`: one toast at a time. */
const val RaftToastLimit: Int = 1

/** The raft-ui command API's timeout for a toast: [RaftToastActionTimeoutMillis] with an action or for an error. */
fun raftToastDefaultDurationMillis(hasAction: Boolean, intent: RaftToastIntent?): Long =
    if (hasAction || intent == RaftToastIntent.Error) RaftToastActionTimeoutMillis else RaftToastTimeoutMillis

/** One toast on a [RaftToastHost]; [durationMillis] 0 stays until dismissed. */
@Immutable
class RaftToastData internal constructor(
    val id: Long,
    val title: String,
    val description: String?,
    val intent: RaftToastIntent?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val durationMillis: Long,
) {
    internal fun isSameMessage(title: String, description: String?, intent: RaftToastIntent?, actionLabel: String?): Boolean =
        this.title == title && this.description == description && this.intent == intent && this.actionLabel == actionLabel
}

/**
 * The toasts of one [RaftToastHost] (raft-ui `ToastProvider` + its toast manager), safe to call
 * from any thread:
 *
 * - [show] adds a toast. At most [limit] show (raft-ui `limit`, 1); a newer one replaces the
 *   oldest at once, and the replaced one is closed, not queued: it does not come back.
 * - The same message again (title, description, intent and action label equal to the newest
 *   toast) does not stack a copy: the newest toast stays and its timer starts over.
 * - Each toast closes by itself after its duration ([raftToastDefaultDurationMillis]: 5s, 8s with
 *   an action or for an error) while [runTimers] runs; [setPaused] (hover, a finger on it)
 *   stops every timer, and the paused time does not count.
 *
 * [timeSource] measures the durations; tests pass the test scheduler's virtual time.
 */
@Stable
class RaftToastHostState(
    val limit: Int = RaftToastLimit,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(limit >= 1) { "limit must be at least 1" }
    }

    private class Entry(val toast: RaftToastData, val deadline: ComparableTimeMark?)

    private class Model(
        val entries: List<Entry> = emptyList(),
        val pausedAt: ComparableTimeMark? = null,
        val nextId: Long = 1,
    ) {
        val toasts: List<RaftToastData> = entries.map { it.toast }

        fun copy(
            entries: List<Entry> = this.entries,
            pausedAt: ComparableTimeMark? = this.pausedAt,
            nextId: Long = this.nextId,
        ) = Model(entries, pausedAt, nextId)
    }

    private val model = MutableStateFlow(Model())
    private val visibleFlow = MutableStateFlow<List<RaftToastData>>(emptyList())

    /** The toasts on screen, oldest first (the newest is the frontmost, at the bottom). */
    val visible: List<RaftToastData> get() = model.value.toasts

    /** [visible] as a flow, for the host. */
    internal val toasts: StateFlow<List<RaftToastData>> get() = visibleFlow

    /** Whether the timers are paused ([setPaused]). */
    val isPaused: Boolean get() = model.value.pausedAt != null

    /**
     * Shows a toast and returns its id. [durationMillis] null takes raft-ui's timeout for it
     * ([raftToastDefaultDurationMillis]); 0 keeps it until [dismiss]. [onAction] runs when the
     * action is tapped, and the toast then closes.
     */
    fun show(
        title: String,
        description: String? = null,
        intent: RaftToastIntent? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMillis: Long? = null,
    ): Long {
        val hasAction = actionLabel != null && onAction != null
        val duration = (durationMillis ?: raftToastDefaultDurationMillis(hasAction, intent)).coerceAtLeast(0)
        var shown = 0L
        val now = timeSource.markNow()
        mutate { m ->
            // Nothing on screen: nothing can be hovered or held either.
            val pausedAt = if (m.entries.isEmpty()) null else m.pausedAt
            // While paused, no time counts: the deadline moves with the pause when it ends.
            val start = pausedAt ?: now
            val deadline = if (duration > 0) start + duration.milliseconds else null
            val newest = m.entries.lastOrNull()
            if (newest != null && newest.toast.isSameMessage(title, description, intent, actionLabel)) {
                shown = newest.toast.id
                val refreshed = RaftToastData(newest.toast.id, title, description, intent, actionLabel, onAction, duration)
                m.copy(entries = m.entries.dropLast(1) + Entry(refreshed, deadline), pausedAt = pausedAt)
            } else {
                shown = m.nextId
                val toast = RaftToastData(m.nextId, title, description, intent, actionLabel, onAction, duration)
                m.copy(entries = (m.entries + Entry(toast, deadline)).takeLast(limit), pausedAt = pausedAt, nextId = m.nextId + 1)
            }
        }
        return shown
    }

    /** Closes the toast [id] (swiped away, its action tapped); a no-op when it is gone. */
    fun dismiss(id: Long) {
        mutate { m -> if (m.entries.none { it.toast.id == id }) m else m.copy(entries = m.entries.filterNot { it.toast.id == id }) }
    }

    /** Closes every toast. */
    fun dismissAll() {
        mutate { m -> m.copy(entries = emptyList(), pausedAt = null) }
    }

    /**
     * Pauses (hover, a press, focus) or resumes every timer. raft-ui: hovering, focusing or
     * touching a toast pauses the timer. The paused time is added to every deadline on resume.
     */
    fun setPaused(paused: Boolean) {
        val now = timeSource.markNow()
        mutate { m ->
            val pausedAt = m.pausedAt
            when {
                paused && pausedAt == null -> m.copy(pausedAt = now)
                !paused && pausedAt != null -> {
                    val held = now - pausedAt
                    m.copy(entries = m.entries.map { Entry(it.toast, it.deadline?.plus(held)) }, pausedAt = null)
                }
                else -> m
            }
        }
    }

    /**
     * Closes each toast when its time is up, until cancelled. The host runs it
     * (`LaunchedEffect`); without it toasts stay until dismissed.
     */
    suspend fun runTimers() {
        model.collectLatest { m ->
            if (m.pausedAt != null) return@collectLatest
            val next = m.entries.mapNotNull { it.deadline }.minOrNull() ?: return@collectLatest
            // Until the deadline has passed on [timeSource] (a dispatcher's clock may wake early).
            while (true) {
                val wait: Duration = next - timeSource.markNow()
                if (!wait.isPositive()) break
                delay(wait)
            }
            val now = timeSource.markNow()
            mutate { current ->
                if (current.pausedAt != null) {
                    current
                } else {
                    val left = current.entries.filter { it.deadline == null || it.deadline > now }
                    if (left.size == current.entries.size) current else current.copy(entries = left)
                }
            }
        }
    }

    private fun mutate(transform: (Model) -> Model) {
        model.update { transform(it) }
        // Published after the model: the flow always ends on the latest model's list.
        visibleFlow.value = model.value.toasts
    }

    // ---- Where the toasts sit (raft-ui `--toast-viewport-bottom`) ----

    /** What holds the bottom of the page, by registrant: the toasts sit above the highest one. */
    internal val avoidances = mutableStateMapOf<Any, RaftToastAvoidance>()

    internal fun putAvoidance(key: Any, avoidance: RaftToastAvoidance) {
        if (avoidances[key] != avoidance) avoidances[key] = avoidance
    }

    internal fun removeAvoidance(key: Any) {
        if (avoidances.containsKey(key)) avoidances.remove(key)
    }

    /** px from the host's bottom edge ([hostBottomPx], in root coordinates) to the highest thing to avoid. */
    internal fun avoidedHeightPx(hostBottomPx: Float): Float =
        avoidances.values.maxOfOrNull { it.heightAbove(hostBottomPx) }?.coerceAtLeast(0f) ?: 0f
}

/** Something a toast must stay above (a tab bar, a composer, a host-drawn bar). */
@Immutable
internal sealed interface RaftToastAvoidance {
    fun heightAbove(hostBottomPx: Float): Float

    /** An element whose top edge is at [topPx] in root coordinates. */
    data class TopEdge(val topPx: Float) : RaftToastAvoidance {
        override fun heightAbove(hostBottomPx: Float): Float = hostBottomPx - topPx
    }

    /** A band [heightPx] tall on the window's bottom edge (a host-drawn tab bar). */
    data class BottomBand(val heightPx: Float) : RaftToastAvoidance {
        override fun heightAbove(hostBottomPx: Float): Float = heightPx
    }
}
