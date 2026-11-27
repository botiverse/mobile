package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * raft-design #776 / #777: a screen reader can't swipe an Activity row or press and hold it, so the row's
 * one accessibility node (role Button, opens the row) carries the row menu's items as custom actions,
 * named as in the menu, in the menu's order, per row kind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ActivityConversationRowAccessibilityTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val mutes = mapOf("general" to false, "launch" to true)

    private fun channel(key: String, unread: Int = 0) =
        InboxItem(key = key, kind = InboxItemKind.Channel, channelId = ChannelId(key), channelName = key, unreadCount = unread)

    private fun thread(key: String, following: Boolean?, unread: Int = 0) =
        InboxItem(
            key = "thread:$key",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(key),
            channelName = "thread",
            threadChannelId = ChannelId(key),
            parentChannelId = ChannelId("mobile"),
            parentChannelName = "mobile",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("parent-$key"),
            isFollowing = following,
            unreadCount = unread,
        )

    @Test
    fun eachRowCarriesItsMenuItemsAsNamedScreenReaderActions() {
        val rows =
            listOf(
                thread("followed", following = true, unread = 2),
                thread("unfollowed", following = false),
                channel("general"),
                channel("launch", unread = 1),
                channel("browse", unread = 3),
                InboxItem(key = "dm", kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm"), channelName = "lagoon"),
            )
        val expected =
            mapOf(
                // A followed thread: the left swipe's Done and Unfollow, the right swipe's Read.
                "thread:followed" to listOf("Mark as read", "Mark as Done", "Unfollow thread"),
                // An unfollowed thread: no read toggle (no right swipe), Done, then Follow.
                "thread:unfollowed" to listOf("Mark as Done", "Follow thread"),
                // A joined channel: Unread on a read row, Done, Mute (or Unmute when muted).
                "general" to listOf("Mark as unread", "Mark as Done", "Mute Activity"),
                "launch" to listOf("Mark as read", "Mark as Done", "Unmute Activity"),
                // A channel with no reachable mute, and a DM: read toggle and Done only.
                "browse" to listOf("Mark as read", "Mark as Done"),
                "dm" to listOf("Mark as unread", "Mark as Done"),
            )
        val ran = mutableListOf<Pair<String, String>>()
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            RaftTheme {
                Column {
                    rows.forEach { item ->
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
                            accessibilityActionIds = activityContextMenuActionIds(item, mutes),
                            onAccessibilityAction = { id -> ran += item.key to id },
                        )
                    }
                }
            }
        }
        settle()
        val root = assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.rootSemanticsNode)
        // The row's node: the one that opens it and carries the actions; the swipe's buttons add none.
        val withActions = nodes.filter { it.config.getOrNull(SemanticsActions.CustomActions) != null }
        assertEquals(rows.size, withActions.size, "one accessibility node with actions per row")
        rows.zip(withActions).forEach { (item, node) ->
            assertNotNull(node.config.getOrNull(SemanticsActions.OnClick), "the row node still opens ${item.key}")
            val actions = node.config[SemanticsActions.CustomActions]
            assertEquals(expected.getValue(item.key), actions.map { it.label }, item.key)
            actions.forEach { action -> assertTrue(action.action(), "${item.key}: ${action.label}") }
        }
        // Each action runs the menu item of its id on its own row.
        assertEquals(rows.flatMap { item -> activityContextMenuActionIds(item, mutes).map { item.key to it } }, ran)
    }

    @Test
    fun taskStatusPixelExposesLocalizedStatusAsContentDescriptionWithoutVisibleBadgeText() {
        val taskItem =
            InboxItem(
                key = "thread:task-46",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("task-46"),
                channelName = "general",
                threadChannelId = ChannelId("task-46"),
                parentChannelId = ChannelId("mobile"),
                parentChannelName = "mobile",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-task-46"),
                parentMessagePreview = "#mobile:#46 Activity two-line preview",
                latestActivityPreview = "Shipped Option A.",
                latestActivitySenderName = "Lagoon",
                isFollowing = true,
                unreadCount = 2,
                taskNumber = 46,
                taskStatus = TaskStatus.InProgress,
                taskConversation = InboxTaskConversation(taskId = TaskId("t-46"), taskTitle = "Activity two-line preview"),
            )
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            RaftTheme {
                ActivityConversationListRow(
                    item = taskItem,
                    model = taskItem.activityConversationRowModel(ActivityConversationListContext(), InboxFilter.All),
                    divider = false,
                    selected = false,
                    timeContext = null,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = taskItem.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                )
            }
        }
        settle()
        val root = assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
        val mergedNodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            mergedNodes += node
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.rootSemanticsNode)
        val rowNode = assertNotNull(mergedNodes.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }, "row button node")
        val descriptions = rowNode.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val texts = rowNode.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("In progress" in descriptions, "phone merged row ContentDescription must carry task status ($descriptions)")
        assertFalse("In progress" in texts, "phone task status pill text must not be rendered visibly ($texts)")

        // Wide layout keeps the single-line `InboxItemLine` with the `sm` status badge text.
        val wideActivity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(wideActivity)
        wideActivity.setContent {
            RaftTheme(scale = RaftScale.Wide) {
                ActivityConversationListRow(
                    item = taskItem,
                    model = taskItem.activityConversationRowModel(ActivityConversationListContext(), InboxFilter.All),
                    divider = false,
                    selected = false,
                    timeContext = null,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = taskItem.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                )
            }
        }
        settle()
        val wideRoot = assertNotNull(findComposeRoot(wideActivity.window.decorView), "no wide compose root")
        val wideNodes = mutableListOf<SemanticsNode>()
        fun walkWide(node: SemanticsNode) {
            wideNodes += node
            node.children.forEach(::walkWide)
        }
        walkWide((wideRoot as ViewRootForTest).semanticsOwner.rootSemanticsNode)
        val wideRowNode = assertNotNull(wideNodes.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }, "wide row button node")
        val wideTexts = wideRowNode.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("In progress" in wideTexts, "wide row keeps status badge text ($wideTexts)")
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
