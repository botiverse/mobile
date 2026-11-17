package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.TabPager
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The agent profile's Activity tab inside the profile's tab pager, as [MembersDetailContent] hosts
 * it: a sideways swipe turns the Overview / Raw Log sub-tabs first and reaches the profile's tabs
 * only past the first or last sub-tab, as nested pagers do. That holds for a swipe on the Activity
 * body and for one on the sub-tab switch itself (a horizontal scroller, which used to hand its
 * leftover straight to the profile's pager).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MembersAgentActivitySubTabSwipeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var selected by mutableIntStateOf(ActivityPage)

    private fun ComposeUiTest.showProfile() {
        selected = ActivityPage
        // The Overview draws avatars: install the app's Coil loader first, as the app does, so this
        // test never creates Coil's default singleton under a later RaftPagerHost test in this JVM.
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    val overview =
                        remember {
                            MembersAgentActivityOverviewBinding(state = MutableStateFlow(agentActivityOverviewScreenshotState()), onIntent = {})
                        }
                    Box(Modifier.fillMaxSize().testTag(PagerTag)) {
                        TabPager(
                            selectedIndex = selected,
                            onSelectedIndexChange = { selected = it },
                            pageCount = 3,
                            keepItemAlive = true,
                        ) { page, _ ->
                            when (page) {
                                ActivityPage ->
                                    MembersAgentActivityTab(
                                        detail = null,
                                        serverId = null,
                                        agentStatusIndicatorState = null,
                                        loadState = MembersAgentActivityLoadState.Idle,
                                        overview = overview,
                                        diagnosticActionState = null,
                                        onCopyDiagnostics = {},
                                        timeContext = TimeContext,
                                        onOpenUrl = {},
                                        autoScrollToLatest = false,
                                        modifier = Modifier.fillMaxSize().testTag(ActivityTag),
                                    )
                                else -> BasicText(if (page == 0) "Profile page" else "Chat page", Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.assertSubTab(label: String) {
        onNodeWithText(label).assertIsSelected()
    }

    /** A swipe across the Activity body, below the sub-tab switch (the Overview / Raw Log content). */
    private fun ComposeUiTest.swipeBody(towardStart: Boolean) {
        onNodeWithTag(ActivityTag).performTouchInput {
            val y = top + height * 0.6f
            val start = Offset(if (towardStart) right - 40f else left + 40f, y)
            val end = Offset(if (towardStart) left + 40f else right - 40f, y)
            swipe(start, end, durationMillis = 300)
        }
        settle()
    }

    @Test
    fun bodySwipeTurnsTheSubTabFirstThenTheProfileTab() = runRobolectricComposeUiTest {
        showProfile()
        assertSubTab(OverviewLabel)

        swipeBody(towardStart = true)
        assertSubTab(RawLabel)
        assertEquals(ActivityPage, selected, "the first swipe turns Overview -> Raw Log, not the profile tab")

        swipeBody(towardStart = true)
        assertEquals(ActivityPage + 1, selected, "past the last sub-tab the swipe turns the profile tab")
    }

    @Test
    fun bodySwipeBackTurnsTheSubTabFirstThenTheProfileTab() = runRobolectricComposeUiTest {
        showProfile()
        swipeBody(towardStart = true)
        assertSubTab(RawLabel)

        swipeBody(towardStart = false)
        assertSubTab(OverviewLabel)
        assertEquals(ActivityPage, selected)

        swipeBody(towardStart = false)
        assertEquals(ActivityPage - 1, selected, "before the first sub-tab the swipe turns the profile tab back")
    }

    @Test
    fun swipeOnTheSubTabSwitchTurnsTheSubTabNotTheProfileTab() = runRobolectricComposeUiTest {
        showProfile()

        onNodeWithContentDescription(SwitchLabel).performTouchInput {
            swipe(Offset(right - 4f, centerY), Offset(left + 4f, centerY), durationMillis = 300)
        }
        settle()

        assertSubTab(RawLabel)
        assertEquals(ActivityPage, selected)
    }

    private companion object {
        const val PagerTag = "agent-profile-pager"
        const val ActivityTag = "agent-activity-tab"
        const val ActivityPage = 1
        const val OverviewLabel = "Overview"
        const val RawLabel = "Raw Log"
        const val SwitchLabel = "Activity view"
        val TimeContext =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                nowEpochMillis = 1_781_870_700_000L,
            )
    }
}
