package build.raft.app.thread

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.app.get

/**
 * How many times the reader has scrolled one thread list by hand. Every programmatic scroll of the
 * list captures it when its intent begins and stands down once it moved (the user-override fence of
 * docs/programmatic-scroll-ownership-contract.md), and the older page waits for it to move past the
 * count its previous page settled at (task #1303: one page per fresh scroll, never a cascade).
 *
 * A finger's drag moves it at every pointer move (the list's touch listener). A mouse wheel or a
 * trackpad moves it too ([onListPointerEvent], task #253): on the desktop app, and on an iPad or an
 * Android device with one, that scroll arrives as [PointerEventType.Scroll] with no button down, which
 * the touch listener never reports, so on the Mac app a thread scrolled up to its "Loading older
 * messages…" row never asked for the older page. The web counts its `wheel` events among the reader's
 * gestures the same way (VirtualMessageTimeline.tsx, GESTURE_EVENTS).
 *
 * The count is snapshot state, read through [observed] by the older-page check: a scroll at the top of
 * the list moves no row and changes no layout, and the check must still see it. [value] reads it
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

    /**
     * One pointer event over the list ([threadListWheelScroll]): a mouse wheel or trackpad scroll up or
     * down counts. A pointer only moving over the list (hover) does not, nor does a sideways swipe (over a
     * wide code block or table), which moves no row of the list.
     */
    fun onListPointerEvent(eventType: PointerEventType, scrollDeltaY: Float) {
        if (eventType == PointerEventType.Scroll && scrollDeltaY.isFinite() && scrollDeltaY != 0f) value += 1
    }
}

/**
 * Feeds every pointer event over the thread list to [ThreadUserScrollGeneration.onListPointerEvent]
 * and (when provided) [ThreadNewerGestureDemandTracker.onListPointerEvent], without consuming it:
 * the list scrolls by the same event.
 */
internal fun Modifier.threadListWheelScroll(
    userScrollGeneration: ThreadUserScrollGeneration,
    newerGestureDemandTracker: ThreadNewerGestureDemandTracker? = null,
): Modifier =
    pointerInput(userScrollGeneration, newerGestureDemandTracker) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val scrollDeltaY = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                userScrollGeneration.onListPointerEvent(
                    eventType = event.type,
                    scrollDeltaY = scrollDeltaY,
                )
                newerGestureDemandTracker?.onListPointerEvent(
                    eventType = event.type,
                    scrollDeltaY = scrollDeltaY,
                )
            }
        }
    }
