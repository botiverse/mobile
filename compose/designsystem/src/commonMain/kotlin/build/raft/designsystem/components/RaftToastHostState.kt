package build.raft.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.Density
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
    val appearAtOnce: Boolean = false,
    val footnote: String? = null,
) {
    internal fun isSameMessage(
        title: String,
        description: String?,
        footnote: String?,
        intent: RaftToastIntent?,
        actionLabel: String?,
    ): Boolean =
        this.title == title &&
            this.description == description &&
            this.footnote == footnote &&
            this.intent == intent &&
            this.actionLabel == actionLabel
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

    /** [visible] as a flow, for hosts and window-level overlay visibility observers. */
    val toasts: StateFlow<List<RaftToastData>> get() = visibleFlow

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
        footnote: String? = null,
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
            if (newest != null && newest.toast.isSameMessage(title, description, footnote, intent, actionLabel)) {
                shown = newest.toast.id
                val refreshed =
                    RaftToastData(
                        id = newest.toast.id,
                        title = title,
                        description = description,
                        intent = intent,
                        actionLabel = actionLabel,
                        onAction = onAction,
                        durationMillis = duration,
                        appearAtOnce = newest.toast.appearAtOnce,
                        footnote = footnote,
                    )
                m.copy(entries = m.entries.dropLast(1) + Entry(refreshed, deadline), pausedAt = pausedAt)
            } else {
                shown = m.nextId
                val toast =
                    RaftToastData(
                        id = m.nextId,
                        title = title,
                        description = description,
                        intent = intent,
                        actionLabel = actionLabel,
                        onAction = onAction,
                        durationMillis = duration,
                        footnote = footnote,
                    )
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

    private fun mutate(carryHeldPageHeightPx: Float = 0f, transform: (Model) -> Model) {
        model.update { transform(it) }
        val toasts = model.value.toasts
        if (toasts.isEmpty()) {
            heldPageHeightPx.value = 0f
        } else if (carryHeldPageHeightPx > 0f) {
            heldPageHeightPx.update { maxOf(it, carryHeldPageHeightPx) }
        }
        // Published after the model and held height: collectors always observe both consistently.
        visibleFlow.value = toasts
    }

    /**
     * Moves every live toast from this host to [target] (when a window leaves the screen for
     * another foreground window), keeping each toast's remaining timeout, suppressing its entry
     * slide (`appearAtOnce = true`), and carrying this host's held bottom height so the toast does
     * not drop onto a shorter bar on [target].
     */
    fun handOffTo(target: RaftToastHostState) {
        if (target === this) return
        val fromNow = timeSource.markNow()
        var drained: List<Entry> = emptyList()
        var drainedPausedAt: ComparableTimeMark? = null
        val carryHeldPx = heldPageHeightPx.value
        mutate { m ->
            drained = m.entries
            drainedPausedAt = m.pausedAt
            if (m.entries.isEmpty()) m else m.copy(entries = emptyList(), pausedAt = null)
        }
        if (drained.isEmpty()) return
        val referenceNow = drainedPausedAt ?: fromNow
        val remainingEntries =
            drained.mapNotNull { entry ->
                val remaining = entry.deadline?.let { it - referenceNow }
                if (remaining != null && !remaining.isPositive()) null else entry.toast to remaining
            }
        if (remainingEntries.isEmpty()) return
        val targetNow = target.timeSource.markNow()
        target.mutate(carryHeldPageHeightPx = carryHeldPx) { m ->
            val pausedAt = if (m.entries.isEmpty()) null else m.pausedAt
            val start = pausedAt ?: targetNow
            var nextId = m.nextId
            val additions =
                remainingEntries.map { (toast, remaining) ->
                    val transferred =
                        RaftToastData(
                            id = nextId++,
                            title = toast.title,
                            description = toast.description,
                            intent = toast.intent,
                            actionLabel = toast.actionLabel,
                            onAction = toast.onAction,
                            durationMillis = toast.durationMillis,
                            appearAtOnce = true,
                            footnote = toast.footnote,
                        )
                    Entry(transferred, remaining?.let { start + it })
                }
            m.copy(
                entries = (m.entries + additions).takeLast(target.limit),
                pausedAt = pausedAt,
                nextId = nextId,
            )
        }
    }

    // ---- Where the toasts sit (raft-ui `--toast-viewport-bottom`) ----

    /** High-water page avoidance height (px) held while a toast stack is on screen; resets when empty. */
    private val heldPageHeightPx = MutableStateFlow(0f)

    internal val currentHeldPageHeightPx: Float get() = heldPageHeightPx.value

    internal class BottomInsetOverride(val key: Any, val provider: Density.() -> Float)

    /** Optional host-provided bottom safe-area + keyboard inset override (px), insertion-ordered (last wins). */
    internal val bottomInsetOverrides = mutableStateListOf<BottomInsetOverride>()

    internal fun putBottomInsetOverride(key: Any, provider: Density.() -> Float) {
        val index = bottomInsetOverrides.indexOfFirst { it.key == key }
        val entry = BottomInsetOverride(key, provider)
        if (index >= 0) bottomInsetOverrides[index] = entry else bottomInsetOverrides.add(entry)
    }

    internal fun removeBottomInsetOverride(key: Any) {
        bottomInsetOverrides.removeAll { it.key == key }
    }

    internal fun bottomInsetOverrideOrNull(): (Density.() -> Float)? =
        bottomInsetOverrides.lastOrNull()?.provider

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

    /** [avoidedHeightPx] with what the keyboard adds left out: the height a toast may hold on to. */
    internal fun pageAvoidedHeightPx(hostBottomPx: Float): Float =
        avoidances.values.maxOfOrNull { it.restingHeightAbove(hostBottomPx) }?.coerceAtLeast(0f) ?: 0f

    /**
     * Resolves the bottom clearance (px, before [RaftToastViewportOffset]) for the current frame's
     * placement pass: monotonically holds [pageAvoidedHeightPx] while toasts are visible (never
     * dropping on page changes or `hostBottomPx` changes), follows taller avoidances and the live
     * keyboard, and never drops below [safeBottomPx].
     */
    internal fun resolveBottomClearancePx(hostBottomPx: Float, safeBottomPx: Float): Float {
        val liveAvoidedPx = avoidedHeightPx(hostBottomPx)
        val pageAvoidedPx = pageAvoidedHeightPx(hostBottomPx)
        val heldPx =
            if (model.value.entries.isEmpty()) {
                0f
            } else {
                val next = maxOf(heldPageHeightPx.value, pageAvoidedPx)
                if (next != heldPageHeightPx.value) heldPageHeightPx.value = next
                next
            }
        return maxOf(heldPx, liveAvoidedPx, safeBottomPx.coerceAtLeast(0f))
    }
}

/** Something a toast must stay above (a tab bar, a composer, a host-drawn bar). */
@Immutable
internal sealed interface RaftToastAvoidance {
    fun heightAbove(hostBottomPx: Float): Float

    /**
     * Resting (non-keyboard) height above [hostBottomPx] that a visible toast may hold on to.
     * Derived from placement-time geometry so live keyboard motion never pollutes
     * [RaftToastHostState.heldPageHeightPx].
     */
    fun restingHeightAbove(hostBottomPx: Float): Float =
        (heightAbove(hostBottomPx) - keyboardPx).coerceAtLeast(0f)

    /** How much of that height is the keyboard under it (a composer rides on the keyboard). */
    val keyboardPx: Float get() = 0f

    /**
     * An element whose top edge was placed at [topPx] in root coordinates with [keyboardPx] of
     * keyboard lift beneath it. When [liveKeyboardPx] is provided, [heightAbove] pairs the
     * placement-time resting height (`hostBottomPx - topPx - keyboardPx`) with the live frame's
     * keyboard lift so [RaftToastHost]'s shallower layout pass stays in lockstep with a deeply
     * nested composer on the very first frame of keyboard open/close before `onPlaced` runs.
     */
    data class TopEdge(
        val topPx: Float,
        override val keyboardPx: Float = 0f,
        val liveKeyboardPx: (() -> Float)? = null,
    ) : RaftToastAvoidance {
        override fun restingHeightAbove(hostBottomPx: Float): Float {
            if (hostBottomPx.isNaN()) return 0f
            val placedKeyboard = keyboardPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
            return (hostBottomPx - topPx - placedKeyboard).coerceAtLeast(0f)
        }

        override fun heightAbove(hostBottomPx: Float): Float {
            if (hostBottomPx.isNaN()) return 0f
            val liveReader = liveKeyboardPx ?: return hostBottomPx - topPx
            val liveKeyboard = liveReader().takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
            return restingHeightAbove(hostBottomPx) + liveKeyboard
        }
    }

    /** A band [heightPx] tall on the window's bottom edge (a host-drawn tab bar). */
    data class BottomBand(val heightPx: Float) : RaftToastAvoidance {
        override fun heightAbove(hostBottomPx: Float): Float = heightPx
    }
}
