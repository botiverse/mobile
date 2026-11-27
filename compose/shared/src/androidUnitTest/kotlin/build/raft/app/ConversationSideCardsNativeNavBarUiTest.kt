package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.shell.LocalRaftNativeNavBar
import build.raft.app.shell.RaftNativeNavBarBackId
import build.raft.app.shell.RaftNativeNavBarBridge
import build.raft.app.shell.RaftNativeNavBarListener
import build.raft.app.shell.RaftNativeTabBarAvatar
import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.ConversationSideCardsCover
import build.raft.app.thread.ConversationSideCardsStore
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderSideCardToggles
import build.raft.app.thread.ThreadPageHeader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for iOS native navigation (`mobile_ios_native_nav_v1`) when a phone conversation
 * opens the Tasks / Files side-card cover (`cs34`):
 * 1. `ConversationSideCardsCover`'s inline `RaftPanelBackHeader` must NOT hijack or clear
 *    `ThreadPageHeader`'s native navigation bar (keeping the channel title, Search, and `⋯` Tasks
 *    toggle intact).
 * 2. `ThreadPageHeader` must publish `hasBack = true` and `nested = true` while the cover is open,
 *    so tapping the native `<` Back button closes the cover (`closeAll()`) instead of popping the
 *    conversation page; once closed, `nested` returns to `false`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class ConversationSideCardsNativeNavBarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private class NativeNavRecorder : RaftNativeNavBarListener {
        var lastTitle: String? = null
        var lastActionsJson: String = "[]"
        var lastHasBack: Boolean = false
        var lastNested: Boolean = false
        var clearedCount: Int = 0

        override fun onNativeNavBarChanged(
            title: String,
            subtitle: String?,
            actionsJson: String,
            extrasJson: String,
            titleMenuJson: String,
            hasBack: Boolean,
            nested: Boolean,
            titleImage: RaftNativeTabBarAvatar?,
        ) {
            lastTitle = title
            lastActionsJson = actionsJson
            lastHasBack = hasBack
            lastNested = nested
        }

        override fun onNativeNavBarCleared() {
            clearedCount += 1
        }

        override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
    }

    @Test
    fun phoneTasksCoverKeepsChannelNativeNavBarAndInterceptsNativeBack() = runRobolectricComposeUiTest {
        val bridge = RaftNativeNavBarBridge()
        val recorder = NativeNavRecorder()
        bridge.attachListener(recorder)
        val store = ConversationSideCardsStore()
        store.open(ConversationSideCard.Tasks)
        var pageBackCalls = 0

        val payload =
            RaftThreadScreenPayload(
                instanceId = "channel-cs34",
                sourceThreadId = "channel-cs34",
                streamChannelId = "channel-cs34",
                title = "engineering",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )

        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val cards by store.state.collectAsState()
            val coverOpen = cards.anyOpen
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftNativeNavBar provides bridge,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ThreadPageHeader(
                            payload = payload,
                            applyTopSafeArea = false,
                            showBack = true,
                            onBack = if (coverOpen) store::closeAll else { { pageBackCalls += 1 } },
                            nativeBackIntercepts = coverOpen,
                            onOpenInChannel = {},
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                            sideCardToggles =
                                ThreadHeaderSideCardToggles(
                                    tasksAvailable = true,
                                    cards = cards,
                                    onToggle = store::toggle,
                                ),
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Text("Channel Messages")
                            ConversationSideCardsCover(
                                cards = cards,
                                onPick = store::focus,
                                onBack = null,
                                onSwipeClose = store::closeAll,
                                tasksContent = { Text("Tasks Board Body") },
                                filesContent = { Text("Files Body") },
                            )
                        }
                    }
                }
            }
        }

        waitForIdle()
        onNodeWithText("Tasks Board Body").assertIsDisplayed()

        // Native bar must still belong to ThreadPageHeader (channel title + Search + More/Tasks),
        // never replaced by ConversationSideCardsCover's "Tasks" sub-header.
        assertEquals("engineering", recorder.lastTitle)
        assertEquals(0, recorder.clearedCount)
        assertTrue(recorder.lastHasBack, "ThreadPageHeader must publish nativeOnBack")
        assertTrue(recorder.lastNested, "Open phone cover must intercept native Back")
        val openRows = Json.parseToJsonElement(recorder.lastActionsJson).jsonArray.map { it.jsonObject }
        val tasksAction = openRows.firstOrNull { it["id"] == JsonPrimitive("thread-side-card-tasks") }
        assertTrue(tasksAction != null, "Tasks toggle must remain reachable in native bar actions: ${recorder.lastActionsJson}")
        assertEquals(JsonPrimitive(true), tasksAction["checked"])
        assertTrue(openRows.any { it["id"] == JsonPrimitive("thread-search") })

        // Tapping native Back closes the cover without popping the page.
        runOnIdle { assertTrue(bridge.actionTapped(RaftNativeNavBarBackId)) }
        waitForIdle()
        assertFalse(store.state.value.anyOpen)
        assertEquals(0, pageBackCalls)
        assertEquals("engineering", recorder.lastTitle)
        assertTrue(recorder.lastHasBack)
        assertFalse(recorder.lastNested, "Closing the cover must release native Back interception")
        val closedRows = Json.parseToJsonElement(recorder.lastActionsJson).jsonArray.map { it.jsonObject }
        val closedTasksAction = closedRows.first { it["id"] == JsonPrimitive("thread-side-card-tasks") }
        assertEquals(null, closedTasksAction["checked"])

        // Re-opening via the native bar's Tasks toggle and toggling it off again also works.
        runOnIdle { assertTrue(bridge.actionTapped("thread-side-card-tasks")) }
        waitForIdle()
        assertTrue(store.state.value.tasks)
        assertTrue(recorder.lastNested)
        runOnIdle { assertTrue(bridge.actionTapped("thread-side-card-tasks")) }
        waitForIdle()
        assertFalse(store.state.value.anyOpen)
        assertFalse(recorder.lastNested)

        // With cover closed, Back now reaches the page-level onBack.
        runOnIdle { assertTrue(bridge.actionTapped(RaftNativeNavBarBackId)) }
        waitForIdle()
        assertEquals(1, pageBackCalls)
    }
}
