package build.raft.app

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * How many times the reader has scrolled one thread list by hand. Every programmatic scroll of the
 * list captures it when its intent begins and stands down once it moved (the user-override fence of
 * docs/programmatic-scroll-ownership-contract.md), and the older page waits for it to move past the
 * count its previous page settled at (task #1303: one page per fresh scroll, never a cascade).
 *
 * A finger's drag moves it at every pointer move (the list's touch listener).
 *
 * The count is snapshot state, read through [observed] by the older-page check. [value] reads it
 * without subscribing, like the plain counter it replaces, for the list's composition and the effects
 * that only compare two counts: they must not recompose or restart on every pointer move.
 */
internal class ThreadUserScrollGeneration {
    private val count = mutableIntStateOf(0)

    /** The count, read without subscribing (composition, effects comparing a captured count). */
    var value: Int
        get() = Snapshot.withoutReadObservation { count.intValue }
        set(next) {
            count.intValue = next
        }

    /** The count, read so that a snapshotFlow or derived state over it re-runs when the reader scrolls. */
    val observed: Int
        get() = count.intValue

    /** One pointer event over the list ([threadListWheelScroll]). */
    @Suppress("UNUSED_PARAMETER")
    fun onListPointerEvent(eventType: PointerEventType, scrollDeltaY: Float) {
        // RED: only a finger's drag (the touch listener) counts.
    }
}

/**
 * Feeds every pointer event over the thread list to [ThreadUserScrollGeneration.onListPointerEvent],
 * without consuming it: the list scrolls by the same event.
 */
internal fun Modifier.threadListWheelScroll(userScrollGeneration: ThreadUserScrollGeneration): Modifier =
    pointerInput(userScrollGeneration) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                userScrollGeneration.onListPointerEvent(
                    eventType = event.type,
                    scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f,
                )
            }
        }
    }
