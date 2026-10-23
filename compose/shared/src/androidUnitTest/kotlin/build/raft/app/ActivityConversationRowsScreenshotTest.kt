package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of Activity conversation-list rows (`screens.activity.rows`): marks beside the
 * excerpt's first line only, plain timestamps, and each channel tile in its channel's own glyph
 * (`#`, joint branch, a private channel's lock under a thread badge). Same capture, tolerance and
 * [RobolectricComposeActivityRule] as [HomeChannelListScreenshotTest]; CI uploads the report
 * folder as `shared-home-screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ActivityConversationRowsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousAppearance = RaftAppThemeState.appearance

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
    }

    @Test
    fun activityRowsLight() = capture("screens.activity.rows", RaftAppAppearance.Light)

    @Test
    fun activityRowsDark() = capture("screens.activity.rows.dark", RaftAppAppearance.Dark)

    /** Rule L swipe toward the end, held open: Unfollow behind a thread row, Mute Activity behind a channel row. */
    @Test
    fun activityRowsSwipeEndLight() =
        capture("screens.activity.rows.swipe-end", RaftAppAppearance.Light) { ActivityRowsSwipeEndVisualContent() }

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        content: @Composable () -> Unit = { ActivityRowsVisualContent(appearance) },
    ) {
        RaftAppThemeState.apply(appearance)
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        verifyOrRecord(name, bitmap)
    }

    private fun verifyOrRecord(name: String, actual: Bitmap) {
        val reportDir = File("build/reports/home-screenshots").apply { mkdirs() }
        File(reportDir, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val golden = File(GOLDEN_DIR, "$name.png")
        if (record) {
            golden.parentFile.mkdirs()
            golden.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("recorded ${golden.path} (${actual.width}x${actual.height})")
            return
        }
        val actualCopy = File(reportDir, "$name.actual.png")
        if (!golden.exists()) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("missing golden ${golden.path}; record with RAFT_SCREENSHOT_RECORD=true (actual: ${actualCopy.path})")
        }
        val expected = BitmapFactory.decodeFile(golden.path) ?: fail("unreadable golden ${golden.path}")
        if (expected.width != actual.width || expected.height != actual.height) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: size ${actual.width}x${actual.height} != golden ${expected.width}x${expected.height}")
        }
        val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        val mismatched = screenshotMismatches(expected, actual, diff)
        val ratio = mismatched.toDouble() / (actual.width * actual.height)
        if (ratio > MAX_MISMATCH_RATIO) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(reportDir, "$name.diff.png").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: $mismatched pixels (${"%.4f".format(ratio * 100)}%) differ from ${golden.path}; actual and diff in ${reportDir.path}")
        }
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val GOLDEN_DIR = "src/androidUnitTest/screenshots"
        const val CHANNEL_DELTA = 8
        const val MAX_MISMATCH_RATIO = 0.001

        val record: Boolean =
            System.getProperty("raft.screenshot.record").toBoolean() ||
                System.getenv("RAFT_SCREENSHOT_RECORD").toBoolean()

        /** Pixels outside ±[CHANNEL_DELTA] on any ARGB channel; paints them red in [diff]. */
        fun screenshotMismatches(expected: Bitmap, actual: Bitmap, diff: Bitmap): Int {
            var mismatched = 0
            for (y in 0 until actual.height) {
                for (x in 0 until actual.width) {
                    val a = actual.getPixel(x, y)
                    val e = expected.getPixel(x, y)
                    val same = (0 until 32 step 8).all { shift -> abs(((a ushr shift) and 0xFF) - ((e ushr shift) and 0xFF)) <= CHANNEL_DELTA }
                    if (same) {
                        diff.setPixel(x, y, (a and 0x00FFFFFF) or 0x40000000)
                    } else {
                        mismatched++
                        diff.setPixel(x, y, 0xFFFF0000.toInt())
                    }
                }
            }
            return mismatched
        }
    }
}

/** Fixed rows and a fixed clock, so the capture is deterministic. */
@Composable
private fun ActivityRowsVisualContent(appearance: RaftAppAppearance) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val rows =
        listOf(
            InboxItem(
                key = "channel:long-press",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("long-press"),
                channelName = "那个长按选中那个功能现在还有问题吗",
                unreadCount = 1,
                hasMention = true,
                lastMessageSenderName = "林舟",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = "长按选中按你说的改好了，PR #3411，等 Lagoon 审完会合进去，下一个测试包就有。",
                lastMessageAt = "2026-10-21T16:31:00Z",
            ),
            // A joint channel: the joint branch glyph, as on its Home row.
            InboxItem(
                key = "channel:joint-hands",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("joint-hands"),
                channelName = "joint-hands",
                channelKind = ConversationKind.Joint,
                channelType = "joint",
                unreadCount = 6,
                lastMessageSenderName = "System",
                lastMessageSenderKind = MemberKind.System,
                lastMessagePreview = "1 new task created: task #50 \"Upgrade admin vite to 6.4.4+ (security)\"",
                lastMessageAt = "2026-10-21T18:26:00Z",
            ),
            InboxItem(
                key = "channel:release",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("release"),
                channelName = "release",
                lastMessageSenderName = "Volta",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = "部署了，而且刚又测了一遍确认现在还生效。",
                lastMessageAt = "2026-10-21T16:17:00Z",
            ),
            // A thread in a channel: the channel tile with the thread badge, "#channel ·" kept.
            InboxItem(
                key = "thread:channel",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel"),
                channelName = "thread",
                unreadCount = 2,
                parentChannelId = ChannelId("mobile"),
                parentChannelName = "mobile",
                parentChannelKind = ConversationKind.Channel,
                parentMessagePreview = "Activity 里私聊的 thread 怎么显示",
                latestActivityPreview = "按 Jasper 的规则来，头像跟着会话走。",
                latestActivitySenderName = "Scout",
                latestActivitySenderId = "agent-scout",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivityAt = "2026-10-21T15:40:00Z",
            ),
            // A thread in a private channel: the lock tile with the thread badge.
            InboxItem(
                key = "thread:private",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-private"),
                channelName = "thread",
                unreadCount = 3,
                parentChannelId = ChannelId("launch-room"),
                parentChannelName = "launch-room",
                parentChannelKind = ConversationKind.Channel,
                parentChannelType = "private",
                parentMessagePreview = "1.15.3 发版清单",
                latestActivityPreview = "预览包测过了，等 artin 点头。",
                latestActivitySenderName = "Volta",
                latestActivitySenderId = "agent-volta",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivityAt = "2026-10-21T15:30:00Z",
            ),
            // A thread in a DM: the peer's face with the thread badge, no "@peer ·" before the sender.
            InboxItem(
                key = "thread:dm",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-dm"),
                channelName = "thread",
                unreadCount = 1,
                parentChannelId = ChannelId("dm-lagoon"),
                parentChannelName = "Lagoon",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessagePreview = "goldens 帮我再录一遍",
                latestActivityPreview = "录好了，亮色和暗色都在。",
                latestActivitySenderName = "Lagoon",
                latestActivitySenderId = "agent-lagoon",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivityAt = "2026-10-21T15:12:00Z",
            ),
        )
    // The Home DM row of the thread's parent DM (an agent peer that is online), and the Home channel
    // rows' glyphs (homeChannelSymbol): `launch-room` is private, `joint-hands` is joint.
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId =
                mapOf("long-press" to "#", "joint-hands" to "git-branch", "release" to "#", "mobile" to "#", "launch-room" to "lock"),
            dmPeersByChannelId =
                mapOf(
                    "dm-lagoon" to
                        ActivityConversationDmPeer(
                            peerId = "agent-lagoon",
                            peerIsAgent = true,
                            avatarWireValue = null,
                            displayName = "Lagoon",
                            activityMuted = false,
                        ),
                ),
        )
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    showDoneAction = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onDone = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Two rows opened toward the end (as while a finger holds them) between two at rest. */
@Composable
private fun ActivityRowsSwipeEndVisualContent() {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val thread =
        InboxItem(
            key = "thread:channel",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-channel"),
            channelName = "thread",
            unreadCount = 2,
            parentChannelId = ChannelId("mobile"),
            parentChannelName = "mobile",
            parentChannelKind = ConversationKind.Channel,
            parentMessagePreview = "Activity 里私聊的 thread 怎么显示",
            latestActivityPreview = "按 Jasper 的规则来，头像跟着会话走。",
            latestActivitySenderName = "Scout",
            latestActivitySenderId = "agent-scout",
            latestActivitySenderKind = MemberKind.Agent,
            latestActivityAt = "2026-10-21T15:40:00Z",
            isFollowing = true,
        )
    val channel =
        InboxItem(
            key = "channel:release",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("release"),
            channelName = "release",
            unreadCount = 1,
            lastMessageSenderName = "Volta",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "部署了，而且刚又测了一遍确认现在还生效。",
            lastMessageAt = "2026-10-21T16:17:00Z",
        )
    val resting =
        InboxItem(
            key = "channel:launch",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("launch-week"),
            channelName = "launch-week",
            lastMessageSenderName = "Lagoon",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Swipe right to unfollow or mute.",
            lastMessageAt = "2026-10-21T14:02:00Z",
        )
    val mutes = mapOf("release" to false, "launch-week" to false)
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId = mapOf("release" to "#", "mobile" to "#", "launch-week" to "#"),
        )
    val rows = listOf(resting to 0f, thread to -0.42f, channel to -0.42f, resting.copy(key = "channel:launch-2") to 0f)
    RaftTheme(appearance = RaftAppearance.Light) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            rows.forEachIndexed { index, (item, reveal) ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    showDoneAction = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onDone = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    onSwipeDone = {},
                    swipeEndAction = item.activitySwipeEndAction(mutes),
                    onSwipeEnd = {},
                    swipePreviewRevealFraction = reveal,
                )
            }
        }
    }
}
