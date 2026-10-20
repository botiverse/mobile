package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject

const val RAFT_CHILD_PAGE_VISIBILITY_PROBE_PAGE = "compose_child_page_visibility_probe"

const val RAFT_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE = "compose_child_page_visibility_active"
private const val RAFT_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE = "compose_child_page_visibility_inactive"

private val ChildPageVisibilityActiveColor = Color(0xFFFF0000)
private val ChildPageVisibilityInactiveColor = Color(0xFF0000FF)

/**
 * Native-host probe for retained AppShell child pages. It stacks two nested
 * [RaftChildPage] instances; the later/inactive page must not remain
 * visually visible after being paused. Before the Android host visibility guard,
 * an inactive retained child could finish rendering late and paint over the
 * active child, which matches the AppShell Tasks-loading-then-Home race shape.
 */
@Page(RAFT_CHILD_PAGE_VISIBILITY_PROBE_PAGE, supportInLocal = true)
internal class RaftChildPageVisibilityProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftChildPageVisibilityProbeScreen() }
    }
}

@Composable
private fun RaftChildPageVisibilityProbeScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        RaftChildPage(
            pageName = RAFT_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE,
            pageData = childPageVisibilityProbeData("active"),
            modifier = Modifier.fillMaxSize(),
            active = true
        )
        RaftChildPage(
            pageName = RAFT_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE,
            pageData = childPageVisibilityProbeData("inactive"),
            modifier = Modifier.fillMaxSize(),
            active = false
        )
    }
}

@Page(RAFT_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE, supportInLocal = true)
internal class RaftChildPageVisibilityActivePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftChildPageVisibilityColor(ChildPageVisibilityActiveColor) }
    }
}

@Page(RAFT_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE, supportInLocal = true)
internal class RaftChildPageVisibilityInactivePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftChildPageVisibilityDelayedColor(ChildPageVisibilityInactiveColor) }
    }
}

@Composable
private fun RaftChildPageVisibilityColor(color: Color) {
    Box(modifier = Modifier.fillMaxSize().background(color))
}

@Composable
private fun RaftChildPageVisibilityDelayedColor(color: Color) {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_000)
        loaded = true
    }
    if (loaded) {
        RaftChildPageVisibilityColor(color)
    } else {
        Box(modifier = Modifier.fillMaxSize())
    }
}

private fun childPageVisibilityProbeData(marker: String): JSONObject =
    JSONObject().apply {
        put("marker", marker)
        put("instanceId", "child-page-visibility-$marker")
    }
