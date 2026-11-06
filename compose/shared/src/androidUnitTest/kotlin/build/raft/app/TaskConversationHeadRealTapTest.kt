package build.raft.app

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipe
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
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin (HANDS Android 16, task #35 in #proj-apps): on the phone, tapping the task head's Assignee
 * (and Status) did nothing. The earlier UI test fired the chips' click actions straight through
 * semantics (performClick), which never asks the page which layer is under the finger. Here the
 * whole task conversation page as the app mounts it (SharedThreadRouteContent: the top bar, the
 * head, the messages, the composer) gets a real finger: a down and an up at the chip's centre,
 * injected at the page's root, so every layer over the head takes part in the hit test.
 */
private const val ShellTag = "task-tap-shell"

@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskConversationHeadRealTapTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    /** Taps the covered shell took (Navigation 3): a finger meant for the task page must never land there. */
    private var shellTaps = 0

    /** Every pointer event the page's root saw last, and whether a layer under it consumed it. */
    private val trace = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        // The page opens the shared SQLDelight stores: without this the class only passed after
        // another test in the same JVM had set the context (run alone, or first in its fork, it
        // failed or hung).
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(org.robolectric.RuntimeEnvironment.getApplication())
        runCatching { build.raft.app.image.raftInstallImageLoader() }
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
    }

    @Test
    fun aFingerOnTheAssigneeOpensTheAssigneePicker() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3)
            tapAtCentreOf(TaskHeadAssigneeTestTag)
            assertOpened("Search assignees", TaskHeadAssigneeTestTag)
        }

    @Test
    fun aFingerOnTheStatusOpensTheStatusMenu() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3)
            tapAtCentreOf(TaskHeadStatusTestTag)
            assertOpened("Todo", TaskHeadStatusTestTag)
        }

    @Test
    fun inALongConversationAtItsNewestMessageAFingerOnTheAssigneeOpensThePicker() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 60)
            tapAtCentreOf(TaskHeadAssigneeTestTag)
            assertOpened("Search assignees", TaskHeadAssigneeTestTag)
        }

    @Test
    fun inALongConversationScrolledBackAFingerOnTheStatusOpensTheMenu() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 60)
            // Back through the messages a little (the Back to latest pill shows): scrolling puts the
            // panel back up (artin 2026-11-06: on a phone the head is only the pull-down panel).
            root().performTouchInput {
                val x = width / 2f
                swipe(Offset(x, height * 0.55f), Offset(x, height * 0.75f), durationMillis = 300)
            }
            settle()
            assertTrue(
                onAllNodesWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "scrolling the messages left the panel open",
            )
            // The bar pulls it down again, where the reader is; a finger on Status opens the menu.
            tapAtCentreOf(TaskBarSummaryTestTag)
            settle()
            tapAtCentreOf(TaskHeadStatusTestTag)
            assertOpened("Todo", TaskHeadStatusTestTag)
        }

    @Test
    fun aFingerOnTheCreatorOpensTheirProfile() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3)
            tapAtCentreOfText("@AppFather")
            assertTrue(
                TaskTapFixture.openedProfiles.any { it.memberId.value == "agent-appfather" },
                "a finger on Created by did not open AppFather's profile (opened ${TaskTapFixture.openedProfiles}); trace:\n" +
                    trace.joinToString("\n"),
            )
        }

    @Test
    fun aFingerOnTheTopBarRefOpensTheTaskChannel() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3)
            tapAtCentreOfText("#proj-apps:#35")
            assertTrue(
                TaskTapFixture.openedConversations.contains(TaskTapParentChannelId),
                "a finger on the bar's #proj-apps:#35 did not open #proj-apps (opened ${TaskTapFixture.openedConversations}); trace:\n" +
                    trace.joinToString("\n"),
            )
        }

    @Test
    fun withNavigation3AFingerOnTheAssigneeOpensTheAssigneePicker() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 60, nav3 = true)
            tapAtCentreOf(TaskHeadAssigneeTestTag)
            assertOpened("Search assignees", TaskHeadAssigneeTestTag)
        }

    @Test
    fun withNavigation3AFingerOnTheStatusOpensTheStatusMenu() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3, nav3 = true)
            tapAtCentreOf(TaskHeadStatusTestTag)
            assertOpened("Todo", TaskHeadStatusTestTag)
        }

    @Test
    fun withNavigation3AFingerOnTheCreatorOpensTheirProfile() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3, nav3 = true)
            tapAtCentreOfText("@AppFather")
            assertTrue(TaskTapFixture.openedProfiles.any { it.memberId.value == "agent-appfather" }, trace.joinToString("\n"))
        }

    @Test
    fun withNavigation3AFingerOnTheTopBarRefOpensTheTaskChannel() =
        runRobolectricComposeUiTest {
            openTaskPage(messages = 3, nav3 = true)
            tapAtCentreOfText("#proj-apps:#35")
            assertTrue(TaskTapFixture.openedConversations.contains(TaskTapParentChannelId), trace.joinToString("\n"))
        }

    private fun ComposeUiTest.openTaskPage(messages: Int, nav3: Boolean = false) {
        TaskTapFixture.reset(messages)
        val pager = object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        val stack = RaftPageStack()
        shellTaps = 0
        setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftHost provides host) {
                RaftDesignSystemTheme {
                    // A spy at the page's root: what reached it and whether something under the
                    // finger consumed it (Final runs after every layer had its turn).
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    event.changes.forEach { change ->
                                        trace += "${event.type} at ${change.position} pressed=${change.pressed} consumed=${change.isConsumed}"
                                    }
                                }
                            }
                        }
                    ) {
                        if (nav3) {
                            // Navigation 3 (RaftAndroidNav3Stack): the task page pushed over the
                            // shell in RaftMobilePageStackDisplay, the shell still under it.
                            RaftMobilePageStackDisplay(stack) { page ->
                                if (page === stack.pages.first()) {
                                    Box(Modifier.fillMaxSize().testTag(ShellTag).clickable { shellTaps++ })
                                } else {
                                    RaftPagerHost(factory = { TaskTapFixturePage() }, args = page.args)
                                }
                            }
                        } else {
                            RaftPagerHost(factory = { TaskTapFixturePage() }, args = remember { taskTapFixturePageArgs() })
                        }
                    }
                }
            }
        }
        if (nav3) {
            waitForIdle()
            runOnIdle { stack.open(taskTapFixturePageArgs().pageName, taskTapFixturePageArgs().pageData, from = stack.pages.first()) }
        }
        // The page loads off the main thread: real time as well as the main looper's.
        repeat(400) {
            if (TaskTapFixture.threadServed && TaskTapFixture.taskServed &&
                onAllNodesWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            ) {
                return@repeat
            }
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            waitForIdle()
        }
        settle()
        assertTrue(
            onAllNodesWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the task head never showed (threadServed=${TaskTapFixture.threadServed} taskServed=${TaskTapFixture.taskServed})\n" +
                root().printToString(Int.MAX_VALUE),
        )
    }

    private fun ComposeUiTest.settle() {
        repeat(20) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            waitForIdle()
        }
    }

    private fun ComposeUiTest.root(): SemanticsNodeInteraction = onAllNodes(isRoot())[0]

    private fun ComposeUiTest.chipBounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** [tapAtCentreOf] for a fact found by its text (the creator's name, the bar's ref). */
    private fun ComposeUiTest.tapAtCentreOfText(text: String) =
        tapAt(text, onAllNodesWithText(text, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot)

    /** A real finger: down and up at the chip's centre, injected at the page's root, not at the chip. */
    private fun ComposeUiTest.tapAtCentreOf(tag: String) = tapAt(tag, chipBounds(tag))

    private fun ComposeUiTest.tapAt(tag: String, bounds: Rect) {
        trace.clear()
        root().performTouchInput {
            down(bounds.center)
            up()
        }
        settle()
        println("task-tap: $tag at $bounds\n" + trace.joinToString("\n"))
        assertEquals(0, shellTaps, "the finger on $tag reached the shell under the task page")
    }

    private fun ComposeUiTest.assertOpened(text: String, tag: String) {
        val opened = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            opened,
            "a finger at the centre of $tag (${chipBounds(tag)}) did not open it; pointer trace at the root:\n" +
                trace.joinToString("\n") + "\nsemantics:\n" + root().printToString(Int.MAX_VALUE),
        )
    }
}
