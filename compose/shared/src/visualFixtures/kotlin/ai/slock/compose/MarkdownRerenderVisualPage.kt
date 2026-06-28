package ai.slock.compose

import ai.slock.compose.base.BasePager
import ai.slock.compose.markdown.SlockMarkdownBody
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.delay

const val SLOCK_MARKDOWN_RERENDER_VISUAL_PAGE = "compose_markdown_rerender_visual"

@Page(SLOCK_MARKDOWN_RERENDER_VISUAL_PAGE, supportInLocal = true)
internal class ComposeMarkdownRerenderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MarkdownRerenderVisualScreen() }
    }
}

@Composable
private fun MarkdownRerenderVisualScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val bodies = remember { MarkdownRerenderBodies }
    var bodyIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        repeat(3) {
            bodies.indices.forEach { index ->
                bodyIndex = index
                delay(32)
            }
        }
        bodyIndex = bodies.lastIndex
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(start = 20.dp, top = (statusBarHeight + 32).dp, end = 20.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(12.dp)
        ) {
            SlockMarkdownBody(
                body = bodies[bodyIndex],
                textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                highlightedMentions = setOf("artin"),
                knownChannelNames = setOf("首页专修", "Kuiklybase", "markdown专修"),
                performanceKey = "markdown-rerender-stable-slot"
            )
        }
    }
}

private val MarkdownRerenderBodies =
    listOf(
        "Round 1 asks @Android-Developer-3 to compare #首页专修 with task #335.",
        "Round 2 checks @Codex-Kuikly-KMP专家 inside #Kuiklybase before task #4.",
        "Final #markdown专修:9579b48a @artin #markdown专修 #37."
    )
