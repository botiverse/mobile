package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageDeliverySent
import build.raft.app.thread.ThreadMessageMenuChrome
import build.raft.app.thread.ThreadNewMessageMenuActionAvailability
import build.raft.app.thread.ThreadNewMessageMenuChrome
import build.raft.app.thread.threadMessageMenuActions
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * botiverse/slock #16225 (menu order v4) & #17092: "Mark channel as unread" and "Mark DM as unread" are
 * gone from both the Compact and Classic message menus (the Home/sidebar row menu owns it); a
 * message whose thread is followed still offers "Mark thread unread" in both menus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMarkConversationUnreadMenuUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-mark-conversation-unread-menu"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun messageMenusOmitMarkChannelAndDmUnreadAndKeepMarkThreadUnread() =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            var markedThread = 0
            setContent {
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        ChannelMessageMenus { markedThread += 1 }
                    }
                }
            }
            waitForIdle()
            onAllNodesWithText("Mark channel as unread", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("Mark DM as unread", useUnmergedTree = true).assertCountEquals(0)
            val items = onAllNodes(hasClickAction() and (hasText("Mark thread unread") or hasContentDescription("Mark thread unread")))
            items.assertCountEquals(2)
            items[0].performSemanticsAction(SemanticsActions.OnClick)
            items[1].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(2, markedThread, "each menu's Mark thread unread runs it once")
        }
}

/**
 * A top-level message's menus in a conversation list whose thread the person follows: the Compact
 * menu, then the Classic menu.
 */
@Composable
internal fun ChannelMessageMenus(onMarkThreadUnread: () -> Unit) {
    val message =
        RaftThreadMessageItem(
            id = "message-1",
            authorId = "member-1",
            authorName = "Lagoon",
            messageType = "Chat",
            body = "Pushed the fix.",
            deliveryState = ThreadMessageDeliverySent
        )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ThreadNewMessageMenuChrome(
            availability =
                ThreadNewMessageMenuActionAvailability(
                    addReaction = true,
                    copyLink = true,
                    followThread = true,
                    threadFollowed = true,
                    markThreadUnread = true,
                ),
            onReact = {},
            onCopy = {},
            onReply = {},
            onForward = {},
            onSave = {},
            onTranslate = {},
            onSelect = {},
            onMore = {},
            onMarkThreadUnread = onMarkThreadUnread,
        )
        ThreadMessageMenuChrome(
            actions =
                threadMessageMenuActions(
                    message = message,
                    showReplyAction = true,
                    showSaveAction = true,
                    showFollowAction = false,
                    showTaskStatusAction = false,
                    showConvertToTaskAction = false,
                    showCopyLinkAction = true,
                    showShareMessagesAction = true,
                    showCancelAction = false,
                    showDeleteAction = false,
                    showForwardAction = true,
                    showMarkThreadUnreadAction = true,
                    onReply = {},
                    onCopyLink = {},
                    onCopy = {},
                    onSelectText = {},
                    onShareMessages = {},
                    onSave = {},
                    onFollowThread = {},
                    onUnfollowThread = {},
                    onTaskStatusChange = {},
                    onConvertToTask = {},
                    onCancel = {},
                    onDelete = {},
                    onMarkThreadUnread = onMarkThreadUnread,
                ),
            showReactionActions = true,
            reactionAtlasImage = null,
            onReact = {},
            onAddReaction = {},
        )
    }
}
