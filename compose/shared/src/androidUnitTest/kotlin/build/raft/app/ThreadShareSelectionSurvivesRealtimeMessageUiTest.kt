package build.raft.app

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.pager.RaftPagerHost
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE
import build.raft.app.data.RAFT_APP_LOGIN_MMKV_NAMESPACE
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.RAFT_DEVICE_STATE_MMKV_NAMESPACE
import build.raft.app.data.RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Characterizes where the page keeps its share (multi-select) selection (ThreadPageContentContent's
 * share / forward state, remembered under the thread instance key, #4997): the whole conversation
 * page as the app mounts it ([TaskTapFixturePage], as [TaskConversationHeadRealTapTest] opens it),
 * two messages selected through the message menu's Select Message and a tap, then a new message of
 * the same conversation arrives over realtime (into the page's own store, as its realtime sink
 * delivers it). The new message shows and the selection is untouched: still "2 selected", the same
 * two rows checked.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ThreadShareSelectionSurvivesRealtimeMessageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        // As TaskConversationHeadRealTapTest: the page opens the shared SQLDelight stores.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(org.robolectric.RuntimeEnvironment.getApplication())
        build.raft.app.image.raftInstallImageLoader()
        listOf(
            RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            RaftLocalPreferenceStore.KuiklyNamespace,
            RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
            RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
            RAFT_DEVICE_STATE_MMKV_NAMESPACE,
            RAFT_APP_LOGIN_MMKV_NAMESPACE,
            SharedApiEnvironmentStore.Namespace,
        ).forEach { namespace ->
            val store = InMemoryRaftKeyValueStore()
            RaftSharedKeyValueStoreProvider.installAppScopeFactory(namespace) { store }
        }
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        TaskTapFixture.reset(messages = 3)
    }

    @Test
    fun aRealtimeMessageLeavesTheShareSelectionAsItWas() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3)
            assertNotNull(TaskTapFixture.threadViewModel, "the fixture did not capture the page's thread view model")
            assertFalse(
                TaskTapFixture.threadViewModelCreatedByFixture,
                "the fixture created its own thread view model: the route's view model key changed",
            )

            // Select mode from the first message's menu (long press -> Select Message): it is selected.
            textNode(FirstBody).performTouchInput { longClick() }
            settle()
            val select = onAllNodes(hasClickAction() and (hasText(SelectMessage) or hasContentDescription(SelectMessage)))
            assertTrue(select.fetchSemanticsNodes().isNotEmpty(), "the long press opened no menu with $SelectMessage\n" + tree())
            select[0].performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertTrue(shows("1 selected"), "Select Message did not start select mode with the message\n" + tree())

            // A tap on the second message's body adds it.
            textNode(SecondBody).performClick()
            settle()
            assertTrue(shows("2 selected"), "the tap did not add the second message\n" + tree())
            val checkedBefore = checkedCount()
            assertTrue(checkedBefore >= 2, "two rows should be checked, $checkedBefore are\n" + tree())

            // A new message of this conversation arrives over realtime.
            runOnIdle { TaskTapFixture.pushRealtimeMessage(id = "task-tap-m4", seq = 4L, body = RealtimeBody) }
            repeat(40) {
                if (shows(RealtimeBody, substring = true)) return@repeat
                settle()
            }
            assertTrue(shows(RealtimeBody, substring = true), "the realtime message never showed\n" + tree())

            // The selection is as it was.
            assertTrue(shows("2 selected"), "the realtime message changed the selection\n" + tree())
            assertEquals(checkedBefore, checkedCount(), "the checked rows changed\n" + tree())
        }

    private fun ComposeUiTest.openTaskPage(messages: Int) {
        TaskTapFixture.reset(messages, captureThreadViewModel = true)
        val pager = object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftHost provides host) {
                RaftDesignSystemTheme {
                    RaftPagerHost(factory = { TaskTapFixturePage() }, args = remember { taskTapFixturePageArgs() })
                }
            }
        }
        // As TaskConversationHeadRealTapTest: the page loads off the main thread.
        repeat(400) {
            if (TaskTapFixture.threadServed && TaskTapFixture.taskServed && headOrBarSummaryShows() && shows(SecondBody, substring = true)) {
                return@repeat
            }
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            waitForIdle()
        }
        settle()
        assertTrue(
            shows(SecondBody, substring = true),
            "the conversation never showed (threadServed=${TaskTapFixture.threadServed} taskServed=${TaskTapFixture.taskServed})\n" + tree(),
        )
    }

    private fun ComposeUiTest.headOrBarSummaryShows(): Boolean =
        onAllNodesWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithTag(TaskBarSummaryTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.settle() {
        repeat(20) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            waitForIdle()
        }
    }

    private fun ComposeUiTest.shows(text: String, substring: Boolean = false): Boolean =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.textNode(text: String): SemanticsNodeInteraction = onAllNodesWithText(text, substring = true)[0]

    /** The checked checkboxes on the page: in select mode, one per selected row. */
    private fun ComposeUiTest.checkedCount(): Int = onAllNodes(isToggleable() and isOn(), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.tree(): String = onAllNodes(isRoot())[0].printToString(Int.MAX_VALUE)

    private companion object {
        /** The fixture's message bodies ("Message N: ..."). */
        const val FirstBody = "Message 1:"
        const val SecondBody = "Message 2:"
        const val RealtimeBody = "A fourth message arrives over realtime"

        /** thread_action_select_message. */
        const val SelectMessage = "Select message"
    }
}
