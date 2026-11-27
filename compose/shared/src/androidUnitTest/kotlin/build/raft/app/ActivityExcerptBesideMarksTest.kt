package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskStatus
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.math.roundToInt
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

    @Test
    fun emptyAndWhitespaceExcerptsKeepFixedHeightWithoutCrashing() {
        listOf("", "   ").forEach { raw ->
            val showMarks = mutableStateOf(true)
            var measuredHeight = -1
            val activity = activities.launch()
            activity.setContent {
                RaftTheme {
                    Box(Modifier.onSizeChanged { measuredHeight = it.height }) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append(raw) },
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            marks = { if (showMarks.value) Box(Modifier.size(28.dp, 20.dp)) },
                        )
                    }
                }
            }
            settle()
            val withMarks = measuredHeight
            showMarks.value = false
            settle()
            assertTrue(withMarks > 0, "positive slot height for '$raw'")
            assertEquals(withMarks, measuredHeight, "fixed height for '$raw'")
        }
    }

    @Test
    fun rtlAndAccessibilityTextAndLongTokenBehaveCleanly() {
        var marksX = -1f
        var rootLeftX = 0f
        var rtlHeight = -1
        val longToken = "https://raft.build/channels/very-long-unbreakable-identifier-0123456789abcdef0123456789abcdef"
        val activity = activities.launch()
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RaftTheme {
                    Box(
                        Modifier
                            .width(280.dp)
                            .onGloballyPositioned {
                                rootLeftX = it.positionInRoot().x
                                rtlHeight = it.size.height
                            }
                    ) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append(longToken) },
                            accessibilityText = "Accessible summary override",
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            marks = {
                                Box(Modifier.onGloballyPositioned { marksX = it.positionInRoot().x }) {
                                    RaftUnreadCount(7, contentDescription = "7 unread")
                                }
                            },
                        )
                    }
                }
            }
        }
        settle()
        val observedTexts = texts(activity)
        assertTrue(rtlHeight > 0, "RTL long token measured positive height")
        assertTrue(marksX <= rootLeftX + 8f, "RTL places marks at physical left edge (marksX=$marksX, rootLeftX=$rootLeftX)")
        assertTrue(
            "Accessible summary override" in observedTexts,
            "accessibilityText is exposed in semantics ($observedTexts)",
        )
        assertTrue(
            observedTexts.none { it.contains("very-long-unbreakable") },
            "visual excerpt text is suppressed when accessibilityText is provided ($observedTexts)",
        )
    }

    @Test
    fun reserveLinesFalseFollowsContentHeightAndClampsAtMaxLines() {
        val shortBody = "stdrc: 可以，合吧。"
        val veryLongBody = excerpt + excerpt + excerpt

        fun measureCase(body: String, withMarks: Boolean): Triple<Int, Pair<String, String?>, List<String>> {
            var measuredHeight = -1
            var latestSplit: Pair<String, String?> = "" to null
            val activity = activities.launch()
            activity.setContent {
                RaftTheme {
                    Box(Modifier.width(300.dp).onSizeChanged { measuredHeight = it.height }) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append(body) },
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            maxLines = 2,
                            reserveLines = false,
                            onLineSplit = { first, rest -> latestSplit = first to rest },
                            marks = {
                                if (withMarks) {
                                    androidx.compose.foundation.layout.Row(
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        RaftTaskStatusPixel(TaskStatus.InProgress.raftTaskStatus())
                                        RaftMentionMarker(contentDescription = "Mentioned")
                                        RaftUnreadCount(13, contentDescription = "13 unread")
                                    }
                                }
                            },
                        )
                    }
                }
            }
            settle()
            return Triple(measuredHeight, latestSplit, texts(activity))
        }

        val (oneLineHeight, oneLineSplit, oneLineTexts) = measureCase(shortBody, withMarks = true)
        val (twoLineHeight, twoLineSplit, twoLineTexts) = measureCase(excerpt, withMarks = true)
        val (twoLineNoMarksHeight, twoLineNoMarksSplit, twoLineNoMarksTexts) = measureCase(excerpt, withMarks = false)
        val (clampedHeight, clampedSplit, _) = measureCase(veryLongBody, withMarks = true)

        assertTrue(oneLineHeight > 0, "1-line excerpt has positive slot height")
        assertNull(oneLineSplit.second, "1-line excerpt has no overflow rest")
        assertEquals(listOf(shortBody), oneLineTexts.filter { it != "13" }, "1-line excerpt keeps one semantics node")

        assertEquals(oneLineHeight * 2, twoLineHeight, "2-line excerpt is exactly 2x slotHeight")
        assertNotNull(twoLineSplit.second, "2-line excerpt overflows onto line 2")
        assertEquals(listOf(excerpt), twoLineTexts.filter { it != "13" }, "2-line excerpt keeps one semantics node with marks")
        assertEquals(listOf(excerpt), twoLineNoMarksTexts, "2-line excerpt keeps one semantics node without marks")
        assertEquals(twoLineHeight, twoLineNoMarksHeight, "2-line height is consistent with or without marks")
        assertTrue(
            twoLineSplit.first.length < twoLineNoMarksSplit.first.length,
            "marks narrow line 1 while line 2 uses full width",
        )

        assertEquals(twoLineHeight, clampedHeight, "3+-line excerpt clamps height to 2x slotHeight")
        assertNotNull(clampedSplit.second, "3+-line excerpt overflows onto line 2")
    }

    @Test
    fun reserveLinesFalseEmptyAndWhitespaceKeepSingleSlotHeight() {
        var referenceOneLineHeight = -1
        activities.launch().setContent {
            RaftTheme {
                Box(Modifier.width(300.dp).onSizeChanged { referenceOneLineHeight = it.height }) {
                    ActivityExcerptBesideMarks(
                        text = buildAnnotatedString { append("hi") },
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foreground,
                        inlineContent = emptyMap(),
                        lineHeight = 20.dp,
                        maxLines = 2,
                        reserveLines = false,
                        marks = {},
                    )
                }
            }
        }
        settle()
        assertTrue(referenceOneLineHeight > 0, "positive 1-line slot height")

        listOf("", "   ").forEach { raw ->
            val showMarks = mutableStateOf(true)
            var measuredHeight = -1
            val activity = activities.launch()
            activity.setContent {
                RaftTheme {
                    Box(Modifier.width(300.dp).onSizeChanged { measuredHeight = it.height }) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append(raw) },
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            maxLines = 2,
                            reserveLines = false,
                            marks = { if (showMarks.value) Box(Modifier.size(28.dp, 20.dp)) },
                        )
                    }
                }
            }
            settle()
            assertEquals(referenceOneLineHeight, measuredHeight, "empty '$raw' with marks keeps 1x slotHeight")
            showMarks.value = false
            settle()
            assertEquals(referenceOneLineHeight, measuredHeight, "empty '$raw' without marks keeps 1x slotHeight")
        }
    }

    @Test
    fun reserveLinesFalseRtlAndAccessibilityTextBehaveCleanly() {
        var marksX = -1f
        var rootLeftX = 0f
        var shortHeight = -1
        var longHeight = -1
        val longToken = "https://raft.build/channels/very-long-unbreakable-identifier-0123456789abcdef0123456789abcdef"

        activities.launch().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RaftTheme {
                    Box(Modifier.width(280.dp).onSizeChanged { shortHeight = it.height }) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append("short") },
                            accessibilityText = "Accessible short override",
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            maxLines = 2,
                            reserveLines = false,
                            marks = { RaftUnreadCount(3, contentDescription = "3 unread") },
                        )
                    }
                }
            }
        }
        settle()

        val activity = activities.launch()
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RaftTheme {
                    Box(
                        Modifier
                            .width(280.dp)
                            .onGloballyPositioned {
                                rootLeftX = it.positionInRoot().x
                                longHeight = it.size.height
                            }
                    ) {
                        ActivityExcerptBesideMarks(
                            text = buildAnnotatedString { append(longToken) },
                            accessibilityText = "Accessible summary override",
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                            inlineContent = emptyMap(),
                            lineHeight = 20.dp,
                            maxLines = 2,
                            reserveLines = false,
                            marks = {
                                Box(Modifier.onGloballyPositioned { marksX = it.positionInRoot().x }) {
                                    RaftUnreadCount(7, contentDescription = "7 unread")
                                }
                            },
                        )
                    }
                }
            }
        }
        settle()
        val observedTexts = texts(activity)
        assertTrue(shortHeight > 0, "RTL short height positive")
        assertEquals(shortHeight * 2, longHeight, "RTL 2-line excerpt is 2x short height")
        assertTrue(marksX <= rootLeftX + 8f, "RTL places marks at physical left edge (marksX=$marksX, rootLeftX=$rootLeftX)")
        assertTrue("Accessible summary override" in observedTexts, "accessibilityText exposed ($observedTexts)")
        assertTrue(observedTexts.none { it.contains("very-long-unbreakable") }, "visual text suppressed ($observedTexts)")
    }

    @Test
    fun phoneRowHeightFollowsContent56dpOneLineAnd76dpTwoLinesWhileWideStays56dp() {
        val shortRow =
            InboxItem(
                key = "thread:short",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("short"),
                channelName = "thread",
                threadChannelId = ChannelId("short"),
                parentChannelId = ChannelId("mobile"),
                parentChannelName = "mobile",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-short"),
                parentMessagePreview = "Short preview title",
                latestActivityPreview = "LGTM.",
                latestActivitySenderName = "stdrc",
                unreadCount = 1,
            )
        val longRow =
            shortRow.copy(
                key = "thread:long",
                channelId = ChannelId("long"),
                threadChannelId = ChannelId("long"),
                latestActivityPreview =
                    "This message is long enough to wrap onto a second line and keep going past the two-line clamp so the row grows to 76dp and ellipsizes cleanly.",
            )
        val veryLongRow =
            longRow.copy(
                key = "thread:verylong",
                channelId = ChannelId("verylong"),
                threadChannelId = ChannelId("verylong"),
                latestActivityPreview = longRow.latestActivityPreview.orEmpty().repeat(3),
            )

        fun measureRowHeightDp(item: InboxItem, scale: RaftScale): Int {
            var heightPx = -1
            var densityValue = 1f
            val activity = activities.launch()
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
            activity.setContent {
                densityValue = LocalDensity.current.density
                RaftTheme(scale = scale) {
                    Box(Modifier.onSizeChanged { heightPx = it.height }) {
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
                        )
                    }
                }
            }
            settle()
            return (heightPx / densityValue).roundToInt()
        }

        assertEquals(56, measureRowHeightDp(shortRow, RaftScale.Compact), "phone 1-line row is 56dp")
        assertEquals(76, measureRowHeightDp(longRow, RaftScale.Compact), "phone 2-line row is 76dp")
        assertEquals(76, measureRowHeightDp(veryLongRow, RaftScale.Compact), "phone 3+-line row clamps at 76dp")
        assertEquals(56, measureRowHeightDp(longRow, RaftScale.Wide), "wide row stays single-line 56dp")
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
