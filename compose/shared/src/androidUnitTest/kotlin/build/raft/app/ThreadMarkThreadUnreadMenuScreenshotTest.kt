package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.base.BridgeModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * botiverse/slock #11687: a reply's long-press menu in a followed reply thread, light and dark. The
 * Compact menu (top) has Mark Thread as Unread on its second row after Select Text and Copy Link;
 * the Classic menu (below, the Compact menu's More) has it right after Save, as web's MessageItem
 * menu in a thread panel. Both with the message-square-dot icon. The menus are the real chrome,
 * drawn in the page (in the app each is its own popup). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ThreadMarkThreadUnreadMenuScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-mark-unread-menu-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun replyMenuLight() = capture("thread.reply-menu.mark-thread-unread", RaftAppAppearance.Light)

    @Test
    fun replyMenuDark() = capture("thread.reply-menu.mark-thread-unread.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftDesignSystemTheme { ReplyMenus() }
            }
        }
}

@Composable
private fun ReplyMenus() {
    val reply =
        RaftThreadMessageItem(
            id = "reply-1",
            authorId = "member-1",
            authorName = "Lagoon",
            messageType = "Chat",
            body = "Pushed the fix.",
            deliveryState = ThreadMessageDeliverySent
        )
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ThreadNewMessageMenuChrome(
            availability =
                ThreadNewMessageMenuActionAvailability(
                    reply = false,
                    addReaction = true,
                    copyLink = true,
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
        )
        ThreadMessageMenuChrome(
            actions =
                threadMessageMenuActions(
                    message = reply,
                    showReplyAction = false,
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
                ),
            showReactionActions = true,
            reactionAtlasImage = null,
            onReact = {},
            onAddReaction = {},
        )
    }
}
