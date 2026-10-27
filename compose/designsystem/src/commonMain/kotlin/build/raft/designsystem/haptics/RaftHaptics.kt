package build.raft.designsystem.haptics

import androidx.compose.runtime.Composable

/**
 * The app's haptics (#proj-mobile task #50, artin: as Telegram does). Only the moment something
 * takes effect buzzes, so a buzz always means "done"; plain taps, sending, scrolling and tab
 * switches stay silent. Four kinds, each the platform's own feedback:
 *
 * | | Android | iOS |
 * |---|---|---|
 * | [longPress]: a long press opens a menu, a drag lifts an item | `LONG_PRESS` | impact, medium |
 * | [threshold]: a gesture crosses its trigger (a row's swipe to its action, a pull past refresh) | `GESTURE_THRESHOLD_ACTIVATE` (API 34; `CONTEXT_CLICK` before) | impact, light |
 * | [tick]: each slot a dragged item passes, each step of a stepped control | `SEGMENT_TICK` (API 34; `CLOCK_TICK` before) | selection changed |
 * | [result]: an action whose outcome isn't otherwise shown succeeded or failed | `CONFIRM` / `REJECT` (API 30; `CONTEXT_CLICK` / `LONG_PRESS` before) | notification, success / error |
 *
 * People who turned touch feedback off in the system settings get none: both platforms honour it.
 * Desktop has no haptics. [prepare] warms iOS's generator ahead of a likely [threshold] or [tick]
 * (a pull or drag starting) so it plays on time; a no-op elsewhere.
 */
interface RaftHaptics {
    fun longPress()

    fun threshold()

    fun tick()

    fun result(success: Boolean)

    fun prepare() {}
}

/** The platform's [RaftHaptics] for the view this is composed in. */
@Composable
expect fun rememberRaftHaptics(): RaftHaptics
