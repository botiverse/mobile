package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Owner, 1.23 (New Message Menu on): "Select Message" in the long-press menu entered message
 * selection, but tapping the messages selected nothing. Two production message rows under the page's
 * in-place selection host (New Message Menu on: a finger press on a body is the row's): a finger long
 * press opens the menu, its Select Message enters selection with that message checked, and finger
 * taps on the rows then check and uncheck them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadSelectMessagesTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The menu's reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-select-messages-tap"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    private var selected: Set<String> = emptySet()
    private var selecting = false

    @Test
    fun selectMessageFromTheMenuThenTapsCheckAndUncheckRows() =
        runRobolectricComposeUiTest {
            showRows()
            onNodeWithText(FIRST_BODY).performTouchInput { longClick() }
            waitForIdle()
            onNodeWithText("Select Message").performClick()
            waitForIdle()
            assertEquals(true, selecting, "Select Message enters message selection")
            assertEquals(setOf(FIRST), selected, "the long-pressed message starts checked")

            onNodeWithText(SECOND_BODY).performTouchInput { click() }
            waitForIdle()
            assertEquals(setOf(FIRST, SECOND), selected, "a tap on another message checks it")

            onNodeWithText(FIRST_BODY).performTouchInput { click() }
            waitForIdle()
            assertEquals(setOf(SECOND), selected, "a tap on a checked message unchecks it")

            onNodeWithText(SECOND_BODY).performTouchInput { click() }
            waitForIdle()
            assertEquals(emptySet(), selected, "the last checked message unchecks too")
        }

    private fun ComposeUiTest.showRows() {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        // As the thread page builds it with the New Message Menu on.
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {}, fingerPressOpensMessageMenu = true)
        setContent {
            var selectedIds by remember { mutableStateOf(emptySet<String>()) }
            var selectionMode by remember { mutableStateOf(false) }
            var menuFor by remember { mutableStateOf<String?>(null) }
            selected = selectedIds
            selecting = selectionMode
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadInPlaceTextSelectionHost provides host,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = RaftPointerInput.Touch) {
                    Column(Modifier.fillMaxWidth()) {
                        listOf(FIRST to FIRST_BODY, SECOND to SECOND_BODY).forEach { (id, body) ->
                            Box(Modifier.fillMaxWidth().testTag(rowTag(id))) {
                                ThreadMessageCard(
                                    message =
                                        RaftThreadMessageItem(
                                            id = id,
                                            authorId = "user-$id",
                                            authorName = "Alice $id",
                                            authorKind = "human",
                                            avatarLabel = "A",
                                            timestampLabel = "2026-11-10T08:00:00Z",
                                            body = body,
                                        ),
                                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                                    timestampLabel = "8:00 AM",
                                    shareSelectionActive = selectionMode,
                                    selectedForShare = id in selectedIds,
                                    onToggleShareSelection = {
                                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                    },
                                    onShowActions = { _ -> menuFor = id },
                                    onOpenMember = {},
                                    onOpenThread = {},
                                    onOpenTask = {},
                                    onOpenTaskStatusMenu = {},
                                    onToggleReaction = {},
                                    onOpenAttachment = {},
                                    onOpenMarkdownTag = { _, _ -> },
                                    onOpenMarkdownUrl = {},
                                )
                            }
                        }
                        menuFor?.let { id ->
                            ThreadNewMessageMenuChrome(
                                availability = ThreadNewMessageMenuActionAvailability(),
                                onReact = {},
                                onCopy = {},
                                onReply = {},
                                onForward = {},
                                onSave = {},
                                onTranslate = {},
                                // ThreadMessagesListContent: close the menu, then onEnterShareSelection.
                                onSelect = {
                                    menuFor = null
                                    selectedIds = setOf(id)
                                    selectionMode = true
                                },
                                onMore = {},
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private companion object {
        const val FIRST = "select-tap-1"
        const val SECOND = "select-tap-2"
        const val FIRST_BODY = "Ship the release notes before the review."
        const val SECOND_BODY = "The Android build is green again."

        fun rowTag(id: String) = "thread_select_messages_tap_row_$id"
    }
}
