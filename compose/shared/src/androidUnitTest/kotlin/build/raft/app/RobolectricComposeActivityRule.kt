package build.raft.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.lang.reflect.Field
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Launches the [ComponentActivity] a Robolectric Compose test draws into, makes sure Compose's
 * main-thread dispatcher is live before the test starts, and takes the activity down afterwards.
 *
 * Why (task #69 CI flake): every Compose window in the Robolectric sandbox runs on one
 * process-wide `AndroidUiDispatcher.Main`. It posts one main-looper message (and one frame
 * callback) per batch and records that it has done so until the message runs. Robolectric drops
 * queued main-looper messages when a test ends. If anything dispatched onto it just before that
 * (typically `GlobalSnapshotManager` waking up because some test wrote snapshot state, even a test
 * that never shows an activity), the message is gone but the dispatcher still believes it is
 * scheduled, and from then on nothing on it runs again in this JVM: no Recomposer, no global
 * snapshot apply notifications. The next activity-based test then fails on whatever it awaits
 * first (the composer IME test's editor never focuses, the IME lift test's SideEffect never runs),
 * depending on test order and timing.
 *
 * - [before]: a dispatcher that claims to be scheduled at the start of a test, when the looper
 *   holds nothing, is wedged; clear its flags and dispatch again so its queued work (the snapshot
 *   manager among it) runs. Compose has no public reset, so this reads its private fields and
 *   fails loudly if they change.
 * - [after]: stop and destroy each launched activity, so its window Recomposer stops asking for
 *   frames, and drain the main looper for a frame so this test leaves nothing in flight.
 */
class RobolectricComposeActivityRule : ExternalResource() {
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()

    fun launch(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }.get()

    override fun before() {
        reviveWedgedComposeDispatcher()
    }

    override fun after() {
        val looper = shadowOf(Looper.getMainLooper())
        controllers.asReversed().forEach { controller ->
            controller.pause().stop().destroy()
            looper.idle()
        }
        controllers.clear()
        // A frame the dispatcher scheduled is due one vsync ahead: run past it. Kept short so the
        // next test's fake clock barely moves.
        looper.idleFor(Duration.ofMillis(34))
    }

    private fun reviveWedgedComposeDispatcher() {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
        val lock = dispatcher.field("lock").get(dispatcher)
        val trampoline = dispatcher.field("scheduledTrampolineDispatch")
        val frame = dispatcher.field("scheduledFrameDispatch")
        val wedged =
            synchronized(lock) {
                val scheduled = trampoline.getBoolean(dispatcher) || frame.getBoolean(dispatcher)
                if (scheduled) {
                    trampoline.setBoolean(dispatcher, false)
                    frame.setBoolean(dispatcher, false)
                }
                scheduled
            }
        if (!wedged) return
        // Posts a fresh trampoline message and frame callback; running them drains the queue.
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
    }

    private fun Any.field(name: String): Field =
        runCatching { javaClass.getDeclaredField(name).apply { isAccessible = true } }.getOrElse {
            error("AndroidUiDispatcher.$name not found: Compose changed; update RobolectricComposeActivityRule")
        }
}
