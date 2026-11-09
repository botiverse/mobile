package build.raft.app

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Back to bottom" pill's scroll inputs reach ThreadMessagesListContent's (very large)
 * composition scope through [rememberThreadBackToBottomViewportSignals]. Scrolling while
 * scrolled up must not recompose the reading scope on every frame; it recomposes only when a
 * signal flips. A control scope that reads layoutInfo directly (the old wiring) proves the
 * scrolls do invalidate per frame. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadBackToBottomSignalsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Counters {
        var derivedScope = 0
        var directScope = 0
        var lastSignals: ThreadBackToBottomViewportSignals? = null
    }

    @Composable
    private fun DerivedReader(viewport: ThreadListViewport, latestItemIndex: Int, counters: Counters) {
        counters.derivedScope++
        val signals by rememberThreadBackToBottomViewportSignals(viewport, latestItemIndex)
        counters.lastSignals = signals
    }

    @Composable
    private fun DirectReader(viewport: ThreadListViewport, counters: Counters) {
        counters.directScope++
        viewport.canScrollForward
        viewport.layoutInfo.visibleItemsInfo.firstOrNull()?.offset
    }

    @Test
    fun scrollingWhileScrolledUpDoesNotRecomposeTheReadingScope() =
        runRobolectricComposeUiTest {
            val counters = Counters()
            lateinit var raw: LazyListState
            setContent {
                raw = rememberLazyListState()
                val viewport = remember(raw) { ThreadListViewport(raw, reverse = false) }
                Box(Modifier.height(300.dp)) {
                    LazyColumn(state = raw) {
                        items(ItemCount) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                    }
                }
                DerivedReader(viewport, latestItemIndex = ItemCount - 1, counters = counters)
                DirectReader(viewport, counters)
            }
            waitForIdle()
            assertEquals(
                ThreadBackToBottomViewportSignals(canScrollTowardBottom = true, latestItemOffBottom = true),
                counters.lastSignals
            )
            val derivedBefore = counters.derivedScope
            val directBefore = counters.directScope
            repeat(ScrollFrames) {
                runOnIdle { runBlocking { raw.scrollBy(7f) } }
                waitForIdle()
            }
            assertTrue(
                counters.directScope - directBefore >= ScrollFrames,
                "control: a direct layoutInfo read should recompose per scroll, got ${counters.directScope - directBefore}"
            )
            assertEquals(derivedBefore, counters.derivedScope, "derived scope recomposed while the signals were unchanged")

            // Reaching the bottom flips both signals: exactly the reading scope's update.
            runOnIdle { runBlocking { raw.scrollToItem(ItemCount - 1) } }
            waitForIdle()
            assertEquals(
                ThreadBackToBottomViewportSignals(canScrollTowardBottom = false, latestItemOffBottom = false),
                counters.lastSignals
            )
            assertTrue(counters.derivedScope > derivedBefore, "derived scope did not update when the signals flipped")
        }

    private companion object {
        const val ItemCount = 40
        const val ScrollFrames = 5
    }
}
