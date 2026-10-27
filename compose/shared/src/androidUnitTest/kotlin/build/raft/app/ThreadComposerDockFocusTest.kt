package build.raft.app

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * artin (dm 59d04ff4): with the keyboard up, a tap on the white strip between the composer card and
 * the keyboard closed the keyboard. That strip is the dock's keyboard lift (its composerModifier
 * padding), outside the composer's own retained region, so the outside-tap dismissal took it.
 * The dock now retains focus over its whole area; below it (the message list) still dismisses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ThreadComposerDockFocusTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aTapOnTheDocksKeyboardLiftKeepsTheComposerFocused() {
        val registry = RaftInputFocusOwnerRegistry()
        var editor: Rect? = null
        var dock: Rect? = null
        var list: Rect? = null
        val activity = activities.launch()
        activity.setContent {
            CompositionLocalProvider(LocalRaftInputFocusOwnerRegistry provides registry) {
                Column {
                    Box(Modifier.fillMaxWidth().height(200.dp).onGloballyPositioned { list = it.boundsInRoot() })
                    Box(Modifier.onGloballyPositioned { dock = it.boundsInRoot() }) {
                        // The keyboard lift, as raftThreadComposerLiveKeyboardPadding adds it.
                        ThreadComposerDock(composerModifier = Modifier.padding(bottom = 48.dp)) {
                            // The composer's own region, as ThreadComposerContentLeaf retains it.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .retainInputFocusForInteractions()
                                    .onGloballyPositioned { editor = it.boundsInRoot() }
                            )
                        }
                    }
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val editorBounds = assertNotNull(editor)
        val dockBounds = assertNotNull(dock)
        val listBounds = assertNotNull(list)
        // A focused editor (its own bounds) arms the outside dismissal.
        registry.setFocused(Any(), true) { editorBounds }

        val liftPoint = Offset(dockBounds.center.x, (editorBounds.bottom + dockBounds.bottom) / 2f)
        assertTrue(liftPoint.y > editorBounds.bottom && liftPoint.y < dockBounds.bottom, "a point in the keyboard lift")
        assertFalse(registry.shouldClearFocus(liftPoint), "a tap under the card keeps the keyboard")
        val topMargin = Offset(dockBounds.center.x, (dockBounds.top + editorBounds.top) / 2f)
        assertFalse(registry.shouldClearFocus(topMargin), "a tap on the dock's top margin keeps the keyboard")
        assertTrue(registry.shouldClearFocus(listBounds.center), "a tap on the message list still closes it")
    }
}
