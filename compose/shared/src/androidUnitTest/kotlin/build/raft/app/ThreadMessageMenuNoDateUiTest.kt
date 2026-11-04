package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodes
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The message menu shows no date or time row (artin, #triage): not the long-press menu on a phone,
 * not the right-click or More menu on desktop. Both menus (Classic, and Compact with its More) are
 * drawn for a message that has a send time, under a touch and a fine pointer, and no text in them
 * reads as a date or a clock time.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageMenuNoDateUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-menu-no-date"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun phoneLongPressMenuHasNoDateRow() = runRobolectricComposeUiTest { assertNoDate(RaftPointerInput.Touch) }

    @Test
    fun desktopContextMenuHasNoDateRow() = runRobolectricComposeUiTest { assertNoDate(RaftPointerInput.Fine) }

    private fun ComposeUiTest.assertNoDate(pointerInput: RaftPointerInput) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = pointerInput) { Menus() }
            }
        }
        waitForIdle()
        // The menus are there: their action rows are named.
        val named = onAllNodes(SemanticsMatcher("has text") { it.texts().isNotEmpty() }, useUnmergedTree = true)
        assertTrue(named.fetchSemanticsNodes().isNotEmpty(), "the menus drew no text")
        onAllNodes(SemanticsMatcher("reads as a date or time") { node -> node.texts().any(DateOrTime::containsMatchIn) }, useUnmergedTree = true)
            .assertCountEquals(0)
        // web's sent-at line was "Jun 22, 2026, 10:30" in Asia/Shanghai.
        onAllNodesWithText("2026", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}

private fun androidx.compose.ui.semantics.SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)

// A year, a clock time ("10:30", "10:30 AM"), or a month and day ("Jun 22", "6月22日").
private val DateOrTime = Regex("""\b(19|20)\d{2}\b|\b\d{1,2}:\d{2}\b|\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \d{1,2}\b|\d{1,2}月\d{1,2}日""")

@Composable
private fun Menus() {
    val message =
        RaftThreadMessageItem(
            id = "message-1",
            authorId = "member-1",
            authorName = "Lagoon",
            messageType = "Chat",
            body = "Pushed the fix.",
            deliveryState = ThreadMessageDeliverySent,
            // 2026-06-22T02:30:00Z, the web fixture's message time.
            createdAtEpochMillis = 1_782_095_400_000L
        )
    Column {
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
                    showMarkThreadUnreadAction = false,
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
