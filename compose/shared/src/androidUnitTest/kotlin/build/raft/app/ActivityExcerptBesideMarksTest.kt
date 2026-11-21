package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * First-principles invariants for [ActivityExcerptBesideMarks]:
 * 1. Line 2 stays intact across marks toggles and subsequent recompositions (artin, dm 37e8b6e9).
 * 2. Row height never changes by a single pixel between unread (marks present) and read (no marks),
 *    across 1-line and 2-line CJK/Latin/mixed excerpts, 19dp inline sender avatars, heavy task/mention
 *    marks, and 1-line wide rows (artin, dm 111c3825).
 * 3. Screen readers hear the excerpt once as a single semantics node (no double-reading of line 2).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ActivityExcerptBesideMarksTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val excerpt =
        "Lagoon: 收到，我来查：动态页点顶部回到最上面以后，又被拉回第二屏。先把这张单的诊断包拉下来看看是哪一步把列表滚回去的。"

    @Test
    fun theSecondLineStaysWhenTheRowIsMeasuredAgain() = secondLineStays(withFace = false)

    /** A row whose sender's face rides inline (an `appendInlineContent` placeholder) before the excerpt. */
    @Test
    fun theSecondLineStaysWithAnInlineFace() = secondLineStays(withFace = true)

    private fun secondLineStays(withFace: Boolean) {
        val text =
            buildAnnotatedString {
                if (withFace) {
                    appendInlineContent("face", "[face]")
                    append(" ")
                }
                append(excerpt)
            }
        val inline =
            if (withFace) {
                mapOf("face" to InlineTextContent(Placeholder(23.sp, 19.sp, PlaceholderVerticalAlign.TextCenter)) { Box(Modifier.size(19.dp)) })
            } else {
                emptyMap()
            }
        val full = text.text
        val showMarks = mutableStateOf(false)
        // Any later recomposition of the row (a new marks lambda, the time ticking) measures it again.
        val tick = mutableStateOf(0)
        var latestSplit: Pair<String, String?> = "" to null
        var measuredHeight = -1
        val activity = activities.launch()
        activity.setContent {
            val t = tick.value
            RaftTheme {
                Box(Modifier.onSizeChanged { measuredHeight = it.height }) {
                    ActivityExcerptBesideMarks(
                        text = text,
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foreground,
                        inlineContent = inline,
                        lineHeight = 20.dp,
                        onLineSplit = { first, rest -> latestSplit = first to rest },
                        marks = { if (showMarks.value && t >= 0) Box(Modifier.size(28.dp, 20.dp)) },
                    )
                }
            }
        }
        settle()
        val readHeight = measuredHeight
        val readSplit = latestSplit
        assertEquals(listOf(full), texts(activity), "no marks: one unified semantics node")
        assertTrue(readHeight > 0, "measured positive height")
        assertNotNull(readSplit.second, "long excerpt wraps onto line 2 without marks")

        // Adding marks narrows line 1 without changing height or duplicating semantics.
        showMarks.value = true
        settle()
        val unreadHeight = measuredHeight
        val unreadSplit = latestSplit
        assertEquals(readHeight, unreadHeight, "read and unread excerpt heights must match to the pixel")
        assertEquals(listOf(full), texts(activity), "marks: still one unified semantics node")
        val rest = assertNotNull(unreadSplit.second, "marks: line 2 must hold the overflow")
        assertTrue(rest.isNotBlank() && full.endsWith(rest) && rest.length < excerpt.length, "line 2 is the rest: $rest")
        assertTrue(unreadSplit.first.length < readSplit.first.length, "marks shorten line 1 so text flows onto line 2")

        // Measured again with the same text and width: line 2 stays intact and height stays fixed.
        tick.value++
        settle()
        assertEquals(unreadSplit, latestSplit, "line 2 is still there after recomposition")
        assertEquals(readHeight, measuredHeight, "height stays invariant after recomposition")

        // Toggling back to read restores full-width line 1 with zero height delta.
        showMarks.value = false
        settle()
        assertEquals(readSplit, latestSplit, "clearing marks restores full-width line 1")
        assertEquals(readHeight, measuredHeight, "height stays identical after clearing marks")
    }

    /**
     * The wide layout's compact row (web `InboxItemLine`): one excerpt line. With marks or without, the
     * whole excerpt stays on one line and keeps the exact same height.
     */
    @Test
    fun oneLineExcerptKeepsTheWholeTextOnOneLineWithFixedHeight() {
        val text = buildAnnotatedString { append(excerpt) }
        val showMarks = mutableStateOf(false)
        var latestRest: String? = "sentinel"
        var measuredHeight = -1
        val activity = activities.launch()
        activity.setContent {
            RaftTheme {
                Box(Modifier.onSizeChanged { measuredHeight = it.height }) {
                    ActivityExcerptBesideMarks(
                        text = text,
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foreground,
                        inlineContent = emptyMap(),
                        lineHeight = 22.dp,
                        maxLines = 1,
                        onLineSplit = { _, rest -> latestRest = rest },
                        marks = { if (showMarks.value) Box(Modifier.size(28.dp, 22.dp)) },
                    )
                }
            }
        }
        settle()
        val readHeight = measuredHeight
        assertEquals(listOf(excerpt), texts(activity), "no marks: one one-line text")
        assertNull(latestRest, "no second line when maxLines = 1")
        showMarks.value = true
        settle()
        assertEquals(listOf(excerpt), texts(activity), "marks: the excerpt stays one text beside them")
        assertNull(latestRest, "still no second line when marks appear")
        assertEquals(readHeight, measuredHeight, "1-line excerpt height stays invariant across marks toggle")
    }

    /**
     * Every combination of excerpt shape (1-line CJK, 2-line CJK, mixed CJK+ASCII, 19dp inline face,
     * heavy task/mention/count marks) must produce the exact same pixel height with marks and without.
     */
    @Test
    fun heightIsInvariantAcrossCjkInlineAvatarAndHeavyMarks() {
        val cases =
            listOf(
                "short-cjk" to "Volta: 部署好了，刚又测了一遍。",
                "two-line-cjk" to excerpt,
                "cjk-then-ascii" to "林舟: 已修复 PR #3411 ready for review and merge after Android + iOS checks pass.",
                "ascii-then-cjk" to "Lagoon: Build 1.28.0 uploaded to TestFlight, 等真机再走一遍分享流程确认一下。",
            )
        val expectedHeights = mutableListOf<Int>()
        cases.forEach { (label, body) ->
            val text =
                buildAnnotatedString {
                    appendInlineContent("face", " ")
                    append(body)
                }
            val inline =
                mapOf("face" to InlineTextContent(Placeholder(23.sp, 19.sp, PlaceholderVerticalAlign.TextCenter)) { Box(Modifier.size(19.dp)) })
            val showMarks = mutableStateOf(true)
            var measuredHeight = -1
            val activity = activities.launch()
            activity.setContent {
                RaftTheme {
                    Box(Modifier.onSizeChanged { measuredHeight = it.height }) {
                        ActivityExcerptBesideMarks(
                            text = text,
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = inline,
                            lineHeight = 20.dp,
                            marks = {
                                if (showMarks.value) {
                                    androidx.compose.foundation.layout.Row(
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        RaftTaskStatusBadge(
                                            status = TaskStatus.InProgress.raftTaskStatus(),
                                            text = "In progress",
                                            size = RaftTaskStatusBadgeSize.Sm,
                                        )
                                        RaftMentionMarker(contentDescription = "Mentioned")
                                        RaftUnreadCount(120, contentDescription = "120 unread")
                                    }
                                }
                            },
                        )
                    }
                }
            }
            settle()
            val unreadHeight = measuredHeight
            showMarks.value = false
            settle()
            val readHeight = measuredHeight
            assertTrue(unreadHeight > 0, "$label: measured positive height")
            assertEquals(unreadHeight, readHeight, "$label: unread ($unreadHeight) != read ($readHeight)")
            expectedHeights += readHeight
        }
        assertEquals(1, expectedHeights.toSet().size, "all 2-line excerpt variants must share one exact height: $expectedHeights")
    }

    /** Full Activity conversation row: tapping an unread CJK row with avatar + mention + count to read must not change row height. */
    @Test
    fun activityConversationRowHeightDoesNotJumpWhenMarkedRead() {
        val unread = mutableStateOf(true)
        var measuredHeight = -1
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            val isUnread = unread.value
            val item =
                InboxItem(
                    key = "channel:cjk-jump",
                    kind = InboxItemKind.Channel,
                    channelId = ChannelId("cjk-jump"),
                    channelName = "那个长按选中那个功能现在还有问题吗",
                    unreadCount = if (isUnread) 3 else 0,
                    hasMention = isUnread,
                    lastMessageSenderId = "agent-linzhou",
                    lastMessageSenderName = "林舟",
                    lastMessageSenderKind = MemberKind.Agent,
                    lastMessagePreview = "长按选中按你说的改好了，PR #3411，等 Lagoon 审完会合进去，下一个测试包就有。",
                    lastMessageAt = "2026-10-21T16:31:00Z",
                )
            RaftTheme {
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(ActivityConversationListContext(), InboxFilter.All),
                    divider = false,
                    selected = false,
                    timeContext = null,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.onSizeChanged { measuredHeight = it.height },
                )
            }
        }
        settle()
        val unreadRowHeight = measuredHeight
        assertTrue(unreadRowHeight > 0, "unread row measured positive height")
        unread.value = false
        settle()
        val readRowHeight = measuredHeight
        assertEquals(unreadRowHeight, readRowHeight, "Activity row height jumped on unread -> read ($unreadRowHeight -> $readRowHeight)")
    }

    private fun texts(activity: android.app.Activity): List<String> {
        val root = assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") { part -> part.text } }
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
