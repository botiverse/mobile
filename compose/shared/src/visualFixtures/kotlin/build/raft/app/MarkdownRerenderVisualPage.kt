package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page

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
    val localPager = LocalActivity.current.getPager() as? BasePager
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val customMarkdown = localPager?.pageData?.params?.optString("rawMarkdown")?.takeIf { it.isNotBlank() }
    val bodies = remember(customMarkdown) {
        if (customMarkdown != null) {
            listOf(customMarkdown)
        } else {
            MarkdownRerenderBodies
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(start = 20.dp, top = (statusBarHeight + 32).dp, end = 20.dp)
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(bodies.size) { index ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .background(SlockColor.SurfaceWhite)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    SlockMarkdownBody(
                        body = bodies[index],
                        textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        highlightedMentions = setOf("artin"),
                        isTagValid = ::isMarkdownRerenderTagValid,
                        performanceKey = "markdown-rerender-list-$index",
                        performanceLogger = { message -> println(message) }
                    )
                }
            }
        }
    }
}

private val MarkdownRerenderBodies =
    List(12) { index ->
        "@artin #markdown专修 item-${index.toString().padStart(2, '0')} task #37"
    }

private val MarkdownRerenderKnownChannelNames =
    setOf("首页专修", "kuiklybase", "markdown专修")

private fun isMarkdownRerenderTagValid(tag: ThreadMarkdownTag): Boolean =
    when (tag.kind) {
        ThreadMarkdownTagKind.Task -> true
        ThreadMarkdownTagKind.UserMention -> tag.label.removePrefix("@").equals("artin", ignoreCase = true)
        ThreadMarkdownTagKind.DirectMessageThread,
        ThreadMarkdownTagKind.DirectMessage ->
            tag.directMessagePeerName.equals("artin", ignoreCase = true)
        ThreadMarkdownTagKind.Channel,
        ThreadMarkdownTagKind.ChannelThread -> {
            val channelKey =
                (tag.channelName ?: tag.label.removePrefix("#").substringBefore(":"))
                    .trim()
                    .lowercase()
            channelKey in MarkdownRerenderKnownChannelNames
        }
    }
