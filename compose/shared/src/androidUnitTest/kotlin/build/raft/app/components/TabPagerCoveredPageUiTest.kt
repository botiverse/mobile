package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.runRobolectricComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page covered by another one on the phone stack is laid out at 0x0 (RaftMobileStackScene). The
 * agent profile's tab pager there must keep the tab the user was on: opening the DM from the
 * profile and coming back showed the last tab (Apps) instead of the first one.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TabPagerCoveredPageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The profile's pager, its selection held in the page's own composition like MembersPage. */
    @Composable
    private fun ProfilePager(onSelection: (Int) -> Unit) {
        var selected by remember { mutableIntStateOf(0) }
        onSelection(selected)
        TabPager(
            selectedIndex = selected,
            onSelectedIndexChange = { selected = it },
            pageCount = TAB_COUNT,
            keepItemAlive = true,
            modifier = Modifier.fillMaxSize().testTag(PAGER),
        ) { page, _ -> Box(Modifier.fillMaxSize().testTag("tab-$page")) }
    }

    @Test
    fun aPagerLaidOutAtZeroSizeKeepsItsSelection() =
        runRobolectricComposeUiTest {
            var covered by mutableStateOf(false)
            var selection = -1
            setContent {
                Box(if (covered) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                    ProfilePager { selection = it }
                }
            }
            waitForIdle()
            assertEquals(0, selection)

            runOnIdle { covered = true }
            waitForIdle()
            assertEquals(0, selection, "covered at 0x0, the pager reported a page the user never chose")

            runOnIdle { covered = false }
            waitForIdle()
            assertEquals(0, selection, "back on screen, the pager shows another tab")
        }

    @Test
    fun aPagerFirstLaidOutAtZeroSizeShowsItsSelectionOnceShown() =
        runRobolectricComposeUiTest {
            var covered by mutableStateOf(true)
            var selection = -1
            setContent {
                Box(if (covered) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                    ProfilePager { selection = it }
                }
            }
            waitForIdle()
            assertEquals(0, selection, "never shown, the pager reported a page the user never chose")

            runOnIdle { covered = false }
            waitForIdle()
            assertEquals(0, selection)
            onNodeWithTag("tab-0").assertIsDisplayed()
        }

    @Test
    fun openingAPageOverTheProfileAndComingBackKeepsTheTab() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            var selection = -1
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    when (page.args.pageName) {
                        PROFILE_PAGE -> ProfilePager { selection = it }
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
            runOnIdle { stack.open(PROFILE_PAGE, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            assertEquals(0, selection)

            // The profile's "message" button opens the DM over it.
            runOnIdle { stack.open(DM_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            assertEquals(0, selection, "under the DM the profile switched tabs")

            runOnIdle { stack.back() }
            waitForIdle()
            assertEquals(PROFILE_PAGE, stack.pages.last().args.pageName)
            assertEquals(0, selection, "back from the DM the profile shows another tab")
        }

    @Test
    fun aSwipeStillSelectsTheNextTab() =
        runRobolectricComposeUiTest {
            var selection = -1
            setContent { ProfilePager { selection = it } }
            waitForIdle()
            onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
            waitForIdle()
            assertEquals(1, selection)
        }

    @Test
    fun aSwipeAfterBeingCoveredStillSelectsTheNextTab() =
        runRobolectricComposeUiTest {
            var covered by mutableStateOf(false)
            var selection = -1
            setContent {
                Box(if (covered) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                    ProfilePager { selection = it }
                }
            }
            waitForIdle()
            runOnIdle { covered = true }
            waitForIdle()
            runOnIdle { covered = false }
            waitForIdle()
            onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
            waitForIdle()
            assertEquals(1, selection)
        }

    private companion object {
        const val TAB_COUNT = 6
        const val PAGER = "pager"
        const val PROFILE_PAGE = "compose_member_detail"
        const val DM_PAGE = "compose_thread"
    }
}
