package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.LocalRaftMarkdownTaskRefStatuses
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.raftMarkdownTaskRefStatusKey
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin: "好像移动端不支持 #triage:#169 这样的？" — conversation-qualified task refs (Web task v2
 * refs) in a message, light and dark: `#triage:#169` (in progress), `#design:#42` (done) and
 * `dm:@Lagoon:#7` (todo) are status chips; `#nope:#9` names a channel the reader does not have and
 * stays plain text; `` `#proj:#7` `` is code. The by-number map deliberately holds other statuses
 * for 169 / 42 / 7: a qualified chip must show its own conversation's task, not the number's.
 * Reports land in `build/reports/thread-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadMessageTaskRefChipsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun taskRefChipsLight() = capture("thread.task-ref-chips", RaftAppAppearance.Light)

    @Test
    fun taskRefChipsDark() = capture("thread.task-ref-chips.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/thread-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMarkdownTaskStatuses provides
                    mapOf(169 to TaskStatus.Closed, 42 to TaskStatus.Todo, 7 to TaskStatus.InReview),
                LocalRaftMarkdownTaskRefStatuses provides taskRefChipStatuses,
            ) {
                RaftDesignSystemTheme { TaskRefChipRows() }
            }
        }
    }
}

private val taskRefChipStatuses: Map<String, TaskStatus> =
    mapOf(
        raftMarkdownTaskRefStatusKey(169, channelName = "triage")!! to TaskStatus.InProgress,
        raftMarkdownTaskRefStatusKey(42, channelName = "design")!! to TaskStatus.Done,
        raftMarkdownTaskRefStatusKey(7, directMessagePeerName = "Lagoon")!! to TaskStatus.Todo,
    )

private fun isTaskRefChipValid(tag: ThreadMarkdownTag): Boolean =
    tag.isKnownThreadMarkdownTag(
        knownChannelNames = setOf("triage", "design"),
        knownMentionNames = emptySet(),
        knownDirectMessagePeerNames = setOf("lagoon"),
    )

private val taskRefBodies =
    listOf(
        "好像移动端不支持 #triage:#169 这样的？#design:#42 已经做完了。",
        "Ask dm:@Lagoon:#7 about it; #nope:#9 is not a channel here, and `#proj:#7` is code.",
    )

@Composable
private fun TaskRefChipRows() {
    val rows =
        taskRefBodies.mapIndexed { index, body ->
            RaftThreadMessageItem(
                id = "task-refs-$index",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-11-03T09:4$index:00Z",
                body = body,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                highlightedMentions = emptySet(),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                timestampLabel = "9:4$index AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                isMarkdownTagValid = ::isTaskRefChipValid,
            )
        }
    }
}
