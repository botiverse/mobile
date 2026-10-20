package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import build.raft.app.base.BasePager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page

const val RAFT_PAGER_SCROLL_PROBE_PAGE = "compose_pager_scroll_probe"

// Param: which page to programmatically scroll to after the first frame.
const val PAGER_SCROLL_PROBE_TARGET_PAGE = "targetPage"

// Distinct full-screen colors so a connected screenshot diff can tell whether the
// visible page actually moved.
private val PagerScrollProbeColors =
    listOf(
        Color(0xFFFF0000), // page 0 — red
        Color(0xFF00FF00), // page 1 — green
        Color(0xFF0000FF) // page 2 — blue
    )

/**
 * Focused probe for the Kuikly pager fix (bytemain/KuiklyUI
 * cc-cata/pager-scrolltopage-native): a [HorizontalPager] with `userScrollEnabled
 * = false` — the exact AppShell child-pager config — whose only way to change the
 * visible page is a programmatic [rememberPagerState.scrollToPage]. Before the fix
 * scrollToPage updated `currentPage` but never drove the native scroll view, so the
 * visible page never moved. The connected test launches this page with different
 * `targetPage` values and asserts the on-screen pixels actually change.
 */
@Page(RAFT_PAGER_SCROLL_PROBE_PAGE, supportInLocal = true)
internal class RaftPagerScrollProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftPagerScrollProbeScreen() }
    }
}

@Composable
private fun RaftPagerScrollProbeScreen() {
    val targetPage =
        LocalActivity.current.pageData.params
            .optInt(PAGER_SCROLL_PROBE_TARGET_PAGE, 0)
            .coerceIn(0, PagerScrollProbeColors.lastIndex)
    val pagerState =
        rememberPagerState(initialPage = 0, pageCount = { PagerScrollProbeColors.size })
    LaunchedEffect(targetPage) {
        if (targetPage != pagerState.currentPage) {
            pagerState.scrollToPage(targetPage)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        key = { page -> page }
    ) { page ->
        Box(modifier = Modifier.fillMaxSize().background(PagerScrollProbeColors[page]))
    }
}
