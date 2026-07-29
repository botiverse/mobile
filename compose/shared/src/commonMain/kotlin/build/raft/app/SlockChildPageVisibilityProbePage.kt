package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

const val SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE = "compose_child_page_visibility_probe"

const val SLOCK_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE = "compose_child_page_visibility_active"
private const val SLOCK_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE = "compose_child_page_visibility_inactive"

private val ChildPageVisibilityActiveColor = Color(0xFFFF0000)
private val ChildPageVisibilityInactiveColor = Color(0xFF0000FF)

/**
 * Native-host probe for retained AppShell child pages. It stacks two nested
 * [SlockKuiklyChildPage] instances; the later/inactive page must not remain
 * visually visible after being paused. Before the Android host visibility guard,
 * an inactive retained child could finish rendering late and paint over the
 * active child, which matches the AppShell Tasks-loading-then-Home race shape.
 */
@Page(SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE, supportInLocal = true)
internal class SlockChildPageVisibilityProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockChildPageVisibilityProbeScreen() }
    }
}

@Composable
private fun SlockChildPageVisibilityProbeScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        SlockKuiklyChildPage(
            pageName = SLOCK_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE,
            pageData = childPageVisibilityProbeData("active"),
            modifier = Modifier.fillMaxSize(),
            active = true
        )
        SlockKuiklyChildPage(
            pageName = SLOCK_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE,
            pageData = childPageVisibilityProbeData("inactive"),
            modifier = Modifier.fillMaxSize(),
            active = false
        )
    }
}

@Page(SLOCK_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE, supportInLocal = true)
internal class SlockChildPageVisibilityActivePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockChildPageVisibilityColor(ChildPageVisibilityActiveColor) }
    }
}

@Page(SLOCK_CHILD_PAGE_VISIBILITY_INACTIVE_PAGE, supportInLocal = true)
internal class SlockChildPageVisibilityInactivePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockChildPageVisibilityDelayedColor(ChildPageVisibilityInactiveColor) }
    }
}

@Composable
private fun SlockChildPageVisibilityColor(color: Color) {
    Box(modifier = Modifier.fillMaxSize().background(color))
}

@Composable
private fun SlockChildPageVisibilityDelayedColor(color: Color) {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_000)
        loaded = true
    }
    if (loaded) {
        SlockChildPageVisibilityColor(color)
    } else {
        Box(modifier = Modifier.fillMaxSize())
    }
}

private fun childPageVisibilityProbeData(marker: String): JSONObject =
    JSONObject().apply {
        put("marker", marker)
        put("instanceId", "child-page-visibility-$marker")
    }
