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
 * artin, 1.24: a long press on a link opens the message menu with the link's section on top, in
 * both menus, light and dark. The Compact menu (top) leads with the address on one line (cut in
 * the middle) and Open, Copy link, Share, Open in browser as tool buttons over a hairline; the
 * Classic menu (below) leads its header with the address and a row per action, over a separator.
 * An external link, so Open in browser shows. The message's own Copy Link reads "Copy Message
 * Link" in both (artin, 1.25). The menus are the real chrome, drawn in the page.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadMessageLinkMenuScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-link-menu-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun linkMenusLight() = capture("thread.message-menu.link-section", RaftAppAppearance.Light)

    @Test
    fun linkMenusDark() = capture("thread.message-menu.link-section.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftDesignSystemTheme { LinkMenus() }
            }
        }
}

@Composable
private fun LinkMenus() {
    val message =
        RaftThreadMessageItem(
            id = "message-1",
            authorId = "member-1",
            authorName = "Lagoon",
            messageType = "Chat",
            body = "Read the docs before the launch review.",
            deliveryState = ThreadMessageDeliverySent
        )
    val link =
        ThreadMessageLinkMenuSection(
            url = "https://docs.example.com/guides/release-checklist/mobile/android-and-ios?section=links#long-press",
            items = threadMessageLinkMenuActions(isRaftLink = false).map { action -> ThreadMessageLinkMenuItem(action) {} }
        )
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ThreadNewMessageMenuChrome(
            availability = ThreadNewMessageMenuActionAvailability(addReaction = true, copyLink = true),
            onReact = {},
            onCopy = {},
            onReply = {},
            onForward = {},
            onSave = {},
            onTranslate = {},
            onSelect = {},
            onMore = {},
            link = link,
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
                    showShareMessagesAction = false,
                    showCancelAction = false,
                    showDeleteAction = false,
                    showForwardAction = false,
                    linkSection = true,
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
            link = link,
        )
    }
}
