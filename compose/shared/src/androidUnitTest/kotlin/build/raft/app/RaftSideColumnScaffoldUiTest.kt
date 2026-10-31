package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The side-column posture reaches a page after its first composition (iPhone Duo). The scaffold
 * must keep the page's composition when it arrives or changes: a remount disposed the thread
 * page's composer image session and retired the just-picked image. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftSideColumnScaffoldUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun postureChangesKeepThePageComposed() =
        runRobolectricComposeUiTest {
            var posture by mutableStateOf(RaftSideColumnPosture.None)
            var disposals = 0
            setContent {
                RaftSideColumnScaffold(posture) {
                    DisposableEffect(Unit) { onDispose { disposals++ } }
                    Box(Modifier.testTag(PAGE))
                }
            }
            onNodeWithTag(PAGE).assertExists()
            runOnIdle { posture = RaftSideColumnPosture(sideColumn = true) }
            onNodeWithTag(PAGE).assertExists()
            runOnIdle { posture = RaftSideColumnPosture.None }
            onNodeWithTag(PAGE).assertExists()
            runOnIdle { assertEquals(0, disposals, "the page was disposed when the posture changed") }
        }

    private companion object {
        const val PAGE = "page"
    }
}
