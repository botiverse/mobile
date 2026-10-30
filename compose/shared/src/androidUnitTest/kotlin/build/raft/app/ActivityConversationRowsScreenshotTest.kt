package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlin.test.Test
import kotlin.test.assertNotNull
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

    /**
     * raft-design #785, rows left open on their buttons: a followed thread (Done, Unfollow), a joined
     * channel (Done, Mute) and a DM thread (Done only), between rows at rest.
     */
    @Test
    fun activityRowsSwipeOpenLight() =
        capture("screens.activity.rows.swipe-open", RaftAppAppearance.Light) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Light, ActivityRowsSwipePose.Open)
        }

    @Test
    fun activityRowsSwipeOpenDark() =
        capture("screens.activity.rows.swipe-open.dark", RaftAppAppearance.Dark) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Dark, ActivityRowsSwipePose.Open)
        }

    /** The same rows as a finger slides them in: the buttons share the uncovered width. */
    @Test
    fun activityRowsSwipeSlidingLight() =
        capture("screens.activity.rows.swipe-sliding", RaftAppAppearance.Light) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Light, ActivityRowsSwipePose.Sliding)
        }

    /** Pulled past 60% of the row: Done covers the uncovered width (release applies it); one row short of it. */
    @Test
    fun activityRowsSwipeFullDoneLight() =
        capture("screens.activity.rows.swipe-full-done", RaftAppAppearance.Light) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Light, ActivityRowsSwipePose.FullDone)
        }

    /**
     * raft-design #793, rows left open on their start button by a right swipe: an unread thread (Read,
     * `message-square-check`) and a read channel (Unread, `message-square-dot`), between rows at rest.
     */
    @Test
    fun activityRowsSwipeReadOpenLight() =
        capture("screens.activity.rows.swipe-read-open", RaftAppAppearance.Light) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Light, ActivityRowsSwipePose.StartOpen)
        }

    @Test
    fun activityRowsSwipeReadOpenDark() =
        capture("screens.activity.rows.swipe-read-open.dark", RaftAppAppearance.Dark) {
            ActivityRowsSwipeVisualContent(RaftAppAppearance.Dark, ActivityRowsSwipePose.StartOpen)
        }

    /**
     * A mouse over the middle row (web ThreadsInbox `InboxItemActions reveal="hover"`): the cell hover fill
     * and the outline Mark as Done button (Lucide Check) in place of its time and unread count.
     */
    @Test
    fun activityRowsHoverDoneLight() =
        capture("screens.activity.rows.hover-done", RaftAppAppearance.Light) {
            ActivityRowsHoverVisualContent(RaftAppAppearance.Light)
        }

    @Test
    fun activityRowsHoverDoneDark() =
        capture("screens.activity.rows.hover-done.dark", RaftAppAppearance.Dark) {
            ActivityRowsHoverVisualContent(RaftAppAppearance.Dark)
        }

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

    private fun verifyOrRecord(name: String, actual: Bitmap) =
        RaftRobolectricScreenshot.verifyOrRecord(name, actual, File("build/reports/home-screenshots"))

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
        // Scrollable like the real LazyColumn: a row past the viewport keeps its own height.
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private enum class ActivityRowsSwipePose { Open, Sliding, FullDone, StartOpen }

/** Swiped rows (open, sliding in, or pulled to full Done) between rows at rest. */
@Composable
private fun ActivityRowsSwipeVisualContent(appearance: RaftAppAppearance, pose: ActivityRowsSwipePose) {
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
    val dm =
        InboxItem(
            key = "dm:lagoon",
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId("dm-lagoon"),
            channelName = "Lagoon",
            channelKind = ConversationKind.DirectMessage,
            unreadCount = 1,
            lastMessageSenderName = "Lagoon",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "goldens 帮我再录一遍",
            lastMessageAt = "2026-10-21T15:12:00Z",
        )
    val resting =
        InboxItem(
            key = "channel:launch",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("launch-week"),
            channelName = "launch-week",
            lastMessageSenderName = "Lagoon",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Swipe left for Done, Unfollow or Mute.",
            lastMessageAt = "2026-10-21T14:02:00Z",
        )
    val mutes = mapOf("release" to false, "launch-week" to false)
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId = mapOf("release" to "#", "mobile" to "#", "launch-week" to "#"),
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
    // (row, open on its buttons, extra slide as a share of the row's width)
    val rows =
        when (pose) {
            // Open on Read (unread thread) and Unread (the read channel), the rows at rest around them.
            ActivityRowsSwipePose.StartOpen ->
                listOf(resting to (false to 0f), thread to (true to 0f), resting.copy(key = "channel:read") to (true to 0f), resting.copy(key = "channel:launch-2") to (false to 0f))
            ActivityRowsSwipePose.Open ->
                listOf(resting to (false to 0f), thread to (true to 0f), channel to (true to 0f), dm to (true to 0f), resting.copy(key = "channel:launch-2") to (false to 0f))
            ActivityRowsSwipePose.Sliding ->
                // The thread row at the mock's 112dp of 390 (past half its 144dp: release opens), the others short of it.
                listOf(resting to (false to 0f), thread to (false to 112f / 390f), channel to (false to 0.22f), dm to (false to 0.08f), resting.copy(key = "channel:launch-2") to (false to 0f))
            ActivityRowsSwipePose.FullDone ->
                listOf(resting to (false to 0f), thread to (false to 0.7f), channel to (false to 0.55f), resting.copy(key = "channel:launch-2") to (false to 0f))
        }
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        // Scrollable like the real LazyColumn: a row past the viewport keeps its own height.
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, (item, rowState) ->
                val (open, slide) = rowState
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    onSwipeDone = {},
                    swipeEndAction = item.activitySwipeEndAction(mutes),
                    onSwipeEnd = {},
                    onSwipeToggleRead = {},
                    swipeOpenSide =
                        when {
                            !open -> null
                            pose == ActivityRowsSwipePose.StartOpen -> ActivitySwipeSide.Start
                            else -> ActivitySwipeSide.End
                        },
                    swipePreviewRevealFraction = slide,
                )
            }
        }
    }
}

/** Latin rows (no CJK goldens on every host), the middle one hovered by a mouse. */
@Composable
private fun ActivityRowsHoverVisualContent(appearance: RaftAppAppearance) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val base =
        InboxItem(
            key = "channel:launch",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("launch-week"),
            channelName = "launch-week",
            lastMessageSenderName = "Lagoon",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Preview build is up; release notes are in the thread.",
            lastMessageAt = "2026-10-21T14:02:00Z",
        )
    val rows =
        listOf(
            base to false,
            base.copy(
                key = "channel:release",
                channelId = ChannelId("release"),
                channelName = "release",
                unreadCount = 3,
                lastMessageSenderName = "Volta",
                lastMessagePreview = "Deployed, and checked again that it still works.",
                lastMessageAt = "2026-10-21T16:17:00Z",
            ) to true,
            base.copy(key = "channel:launch-2") to false,
        )
    val context = ActivityConversationListContext(channelSymbolsByChannelId = mapOf("launch-week" to "#", "release" to "#"))
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, (item, hovered) ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = false,
                    timeContext = timeContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    onSwipeDone = {},
                    onHoverDone = {},
                    hoverPreview = hovered,
                )
            }
        }
    }
}
