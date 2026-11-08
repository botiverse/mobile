package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The conversation's Chat / Tasks / Files pager with swiping off (desktop,
 * [raftPlatformTabPagerSwipeEnabled]): a drag or a trackpad's sideways scroll over Chat never
 * turns it to another tab (artin 2026-11-03: a DM he kept on Chat showed Files); the tab strip
 * still switches. With swiping on (phones) the same drag pages as before.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TabPagerSwipeGateUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var selected by mutableIntStateOf(0)

    private fun ComposeUiTest.showPager(swipeEnabled: Boolean) {
        selected = 0
        setContent {
            Box(Modifier.fillMaxSize().testTag(PagerTag)) {
                TabPager(
                    selectedIndex = selected,
                    onSelectedIndexChange = { selected = it },
                    pageCount = 3,
                    gesturePolicy = TabPagerGesturePolicy(swipeEnabled = swipeEnabled),
                    keepItemAlive = true,
                ) { page, _ ->
                    BasicText(listOf("Chat", "Tasks", "Files")[page], Modifier.fillMaxSize())
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun swipeOffKeepsChatUnderDragsAndSidewaysScroll() = runRobolectricComposeUiTest {
        showPager(swipeEnabled = false)

        onNodeWithTag(PagerTag).performTouchInput { swipeLeft() }
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(PagerTag).performMouseInput { scroll(600f, ScrollWheel.Horizontal) }
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(PagerTag).performMouseInput { scroll(600f, ScrollWheel.Horizontal) }
        mainClock.advanceTimeBy(2_000)

        assertEquals(0, selected)
        onNodeWithText("Chat").assertIsDisplayed()
    }

    @Test
    fun swipeOffStillFollowsTheTabStrip() = runRobolectricComposeUiTest {
        showPager(swipeEnabled = false)

        runOnIdle { selected = 2 }
        mainClock.advanceTimeBy(2_000)

        onNodeWithText("Files").assertIsDisplayed()
        assertEquals(2, selected)
    }

    @Test
    fun swipeOnPagesWithADrag() = runRobolectricComposeUiTest {
        showPager(swipeEnabled = true)

        onNodeWithTag(PagerTag).performTouchInput { swipeLeft() }
        mainClock.advanceTimeBy(2_000)

        assertEquals(1, selected)
    }

    private companion object {
        const val PagerTag = "tab-pager"
    }
}
