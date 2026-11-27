package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.shell.RaftHostWindowInsets
import build.raft.app.thread.ThreadArchivedChannelNotice
import build.raft.app.thread.ThreadArchivedChannelTestTags
import build.raft.app.thread.ThreadFooterNoticeDock
import build.raft.app.thread.ThreadJoinFooter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Conversation non-composer footers ([ThreadArchivedChannelNotice], [AgentLoungeFooter],
 * [ThreadJoinFooter]) sit at the bottom of an edge-to-edge thread page and must stay above the
 * Android system navigation bar in both 3-button and gesture navigation (#proj-mobile:#232).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xxhdpi")
class ThreadFooterBottomInsetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private val createdWindowIds = mutableListOf<String>()

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
        createdWindowIds.forEach(RaftHostWindowInsets::remove)
        createdWindowIds.clear()
    }

    @Test
    fun archivedChannelNoticeClearsThreeButtonNavigationBar() =
        // xxhdpi = 3px/dp: 144px = 48dp (Android 3-button nav).
        assertArchivedNoticeClearsNavBar(navBarPx = 144, minClearancePx = 144f)

    @Test
    fun joinFooterClearsGestureNavigationBar() =
        assertJoinFooterClearsNavBar(navBarPx = 72, minClearancePx = 72f)

    @Test
    fun joinFooterKeepsBaselinePaddingWithoutSystemInset() =
        assertJoinFooterClearsNavBar(navBarPx = 0, minClearancePx = 30f)

    private fun assertArchivedNoticeClearsNavBar(navBarPx: Int, minClearancePx: Float) =
        runRobolectricComposeUiTest {
            showAtBottom(navBarPx = navBarPx) {
                ThreadFooterNoticeDock {
                    ThreadArchivedChannelNotice(canUnarchive = true, unarchiving = false, onUnarchive = {})
                }
            }
            val pageBottom = onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot.bottom
            val noticeBottom = onNodeWithTag(ThreadArchivedChannelTestTags.Notice).fetchSemanticsNode().boundsInRoot.bottom
            val unarchiveBottom = onNodeWithTag(ThreadArchivedChannelTestTags.UnarchiveAction).fetchSemanticsNode().boundsInRoot.bottom
            assertTrue(
                pageBottom - noticeBottom >= minClearancePx,
                "Expected archived notice to clear ${minClearancePx}px nav bar (pageBottom=$pageBottom, noticeBottom=$noticeBottom)",
            )
            assertTrue(
                pageBottom - unarchiveBottom >= minClearancePx,
                "Expected Unarchive button to clear ${minClearancePx}px nav bar (pageBottom=$pageBottom, unarchiveBottom=$unarchiveBottom)",
            )
        }

    private fun assertJoinFooterClearsNavBar(navBarPx: Int, minClearancePx: Float) =
        runRobolectricComposeUiTest {
            showAtBottom(navBarPx = navBarPx) {
                ThreadJoinFooter(label = "Join channel", onJoin = {})
            }
            val pageBottom = onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot.bottom
            val joinBottom = onNodeWithText("Join channel").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue(
                pageBottom - joinBottom >= minClearancePx,
                "Expected Join footer button to clear ${minClearancePx}px nav bar (pageBottom=$pageBottom, joinBottom=$joinBottom)",
            )
        }

    private fun ComposeUiTest.showAtBottom(
        navBarPx: Int,
        hostExcludesNavBar: Boolean = false,
        footer: @Composable () -> Unit,
    ) {
        val windowId = "thread-footer-inset-$navBarPx-$hostExcludesNavBar-${nextWindowId++}"
        createdWindowIds += windowId
        setContent {
            val pager =
                remember(windowId) {
                    object : Pager() {}.apply {
                        pageData =
                            PageData(
                                JSONObject().apply {
                                    put("slockWindowId", windowId)
                                    put("navigationBarHeight", navBarPx.toString())
                                    put("safeAreaBottom", navBarPx.toString())
                                    put("slockHostExcludesNavigationBar", hostExcludesNavBar)
                                }
                            )
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().testTag(PAGE_TAG)) {
                        Spacer(Modifier.weight(1f).fillMaxWidth())
                        Box(Modifier.fillMaxWidth()) { footer() }
                    }
                }
            }
        }
        waitForIdle()
    }

    private companion object {
        const val PAGE_TAG = "thread-footer-inset-page"
        var nextWindowId = 1
    }
}
