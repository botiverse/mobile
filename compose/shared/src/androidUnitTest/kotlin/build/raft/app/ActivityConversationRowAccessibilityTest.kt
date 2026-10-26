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
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
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
                "thread:followed" to listOf("Mark as Read", "Mark as Done", "Unfollow Thread"),
                // An unfollowed thread: no read toggle (no right swipe), Done, then Follow.
                "thread:unfollowed" to listOf("Mark as Done", "Follow Thread"),
                // A joined channel: Unread on a read row, Done, Mute (or Unmute when muted).
                "general" to listOf("Mark as Unread", "Mark as Done", "Mute Activity"),
                "launch" to listOf("Mark as Read", "Mark as Done", "Unmute Activity"),
                // A channel with no reachable mute, and a DM: read toggle and Done only.
                "browse" to listOf("Mark as Read", "Mark as Done"),
                "dm" to listOf("Mark as Unread", "Mark as Done"),
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
    fun aRowWithoutActionIdsPublishesNoCustomActions() {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val item = channel("general")
        activity.setContent {
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
                )
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
        assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Role) != null }, "the row node is there")
        assertTrue(nodes.none { it.config.getOrNull(SemanticsActions.CustomActions) != null })
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
