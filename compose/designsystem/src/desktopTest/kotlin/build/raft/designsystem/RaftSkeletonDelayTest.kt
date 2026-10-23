package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftListSkeletonDelayMillis
import build.raft.designsystem.components.RaftListSkeletonDelayMillis
import build.raft.designsystem.components.RaftSkeletonList
import build.raft.designsystem.components.rememberRaftSkeletonDelayPassed
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * raft-ui 0.22.1 `InboxItemSkeletonList` `delay` (#720): a list skeleton shows only after 1s
 * of loading. On the test's main clock (virtual time), never the wall clock.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSkeletonDelayTest {
    @Test
    fun listSkeletonWaitsOneSecond() =
        runComposeUiTest {
            assertEquals(1000L, RaftListSkeletonDelayMillis)
            mainClock.autoAdvance = false
            setContent { RaftTheme { RaftSkeletonList { Box(Modifier.size(10.dp).testTag("row")) } } }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("row").assertDoesNotExist()
            mainClock.advanceTimeBy(900)
            onNodeWithTag("row").assertDoesNotExist()
            mainClock.advanceTimeBy(200)
            onNodeWithTag("row").assertExists()
        }

    @Test
    fun zeroDelayShowsInTheFirstFrame() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                RaftTheme {
                    // The parameter, and the local screenshot fixtures provide.
                    RaftSkeletonList(delayMillis = 0L) { Box(Modifier.size(10.dp).testTag("param")) }
                    CompositionLocalProvider(LocalRaftListSkeletonDelayMillis provides 0L) {
                        RaftSkeletonList { Box(Modifier.size(10.dp).testTag("local")) }
                    }
                }
            }
            onNodeWithTag("param").assertExists()
            onNodeWithTag("local").assertExists()
        }

    @Test
    fun aNewLoadWaitsAgain() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var loading by mutableStateOf(true)
            setContent {
                RaftTheme {
                    if (rememberRaftSkeletonDelayPassed(loading = loading)) Box(Modifier.size(10.dp).testTag("row"))
                }
            }
            mainClock.advanceTimeBy(1100)
            onNodeWithTag("row").assertExists()
            loading = false
            mainClock.advanceTimeByFrame()
            onNodeWithTag("row").assertDoesNotExist()
            loading = true
            mainClock.advanceTimeByFrame()
            onNodeWithTag("row").assertDoesNotExist()
            mainClock.advanceTimeBy(1100)
            onNodeWithTag("row").assertExists()
        }
}
