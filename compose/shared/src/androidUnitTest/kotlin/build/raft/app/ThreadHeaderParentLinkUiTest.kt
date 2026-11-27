package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.TaskStatus
import build.raft.app.resources.StringDesc
import build.raft.app.settings.observe
import build.raft.app.shell.LocalRaftNativeNavBar
import build.raft.app.shell.LocalRaftNativeNavBarScript
import build.raft.app.shell.RaftNativeNavBarBridge
import build.raft.app.shell.RaftNativeNavBarListener
import build.raft.app.shell.RaftNativeTabBarAvatar
import build.raft.app.thread.LocalThreadHeaderMenuEventBus
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.ThreadReplyPageTitle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A reply thread's header on a real composition, against the web ThreadPanelHeader
 * (`thread-header-channel-link` / `thread-header-reply-count`): the conversation the thread belongs
 * to, a link that opens the parent message there, then " · N replies". The Compose head and the
 * iOS native bar (mobile_ios_native_nav_v1) carry the same line and the same tap. A task
 * conversation's bar links its ref's place the same way (web TaskConversationBar, #16258).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h400dp")
class ThreadHeaderParentLinkUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private object QuietEventBus : RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) = RealtimeSubscription {}

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    private val payload =
        RaftThreadScreenPayload(
            instanceId = "reply-thread",
            title = ThreadReplyPageTitle,
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            parentChannelId = "dm-lagoon",
            parentMessageId = "msg-1",
            sourceThreadId = "thread-1",
        )

    private val origin =
        RaftThreadMessageItem(id = "msg-1", authorId = "user-1", authorName = "artin", authorHandle = "artin", body = "1.23 why")

    private fun ComposeUiTest.showHeader(
        replyCount: Int?,
        onOpenInChannel: () -> Unit,
        nativeBar: RaftNativeNavBarBridge? = null,
        headerPayload: RaftThreadScreenPayload = payload,
        display: ThreadHeaderDisplayModel =
            ThreadHeaderDisplayModel(
                title = ThreadReplyPageTitle,
                subtitle = "@Lagoon",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "dm",
            ),
        taskChrome: TaskConversationHeaderChrome? = null,
    ) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
                LocalRaftNativeNavBar provides nativeBar,
                LocalRaftNativeNavBarScript provides nativeBar,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ThreadPageHeader(
                            payload = headerPayload,
                            headerDisplay = display,
                            originMessage = origin,
                            originReplyCount = replyCount,
                            applyTopSafeArea = false,
                            onBack = {},
                            onOpenInChannel = onOpenInChannel,
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                            taskConversationChrome = taskChrome,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theConversationNameOpensTheParentMessageAndTheCountFollowsIt() =
        runRobolectricComposeUiTest {
            var opens = 0
            showHeader(replyCount = 3, onOpenInChannel = { opens++ })
            // The original is in view (or no list reports it): the title is the thread's own name.
            onNodeWithText("Thread").assertExists()
            onNodeWithText(" · 3 replies").assertExists()
            onNodeWithText("@Lagoon").performClick()
            runOnIdle { assertEquals(1, opens) }
        }

    /**
     * Web #16258 (TaskConversationBar, `task-modal-bar-channel-link`): a task conversation's ref names
     * its place as the reply thread's head names its conversation, a link to the task in its channel
     * (View in Channel); the task's number after it stays text.
     */
    @Test
    fun aTaskRefsPlaceOpensItsChannelAndItsNumberStaysText() =
        runRobolectricComposeUiTest {
            var views = 0
            showHeader(
                replyCount = null,
                onOpenInChannel = {},
                headerPayload =
                    RaftThreadScreenPayload(
                        instanceId = "task-conversation-24",
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftTaskConversationChannelKind,
                        showModeTabs = false,
                        showSettingsAction = false,
                        showMembersAction = false,
                    ),
                display =
                    ThreadHeaderDisplayModel(
                        title = "#video-production:#24 Cut the launch video",
                        subtitle = null,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftTaskConversationChannelKind,
                    ),
                taskChrome =
                    TaskConversationHeaderChrome(
                        ref = "#video-production:#24",
                        taskNumber = 24,
                        status = TaskStatus.Done,
                        assigneeName = null,
                        summarized = false,
                        followed = null,
                        onToggleHeadFloat = {},
                        onViewInChannel = { views++ },
                        onToggleFollow = {},
                    ),
            )
            onNodeWithText(":#24", useUnmergedTree = true).assertExists()
            onNode(hasText(":#24") and hasClickAction(), useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("#video-production").performClick()
            runOnIdle { assertEquals(1, views) }
        }

    /**
     * #proj-mobile:#281: the conversation head on the iOS native bar follows web Task v2 order
     * (Search, Tasks, Files, Settings — no Members button), folding from the last into ⋯.
     */
    @Test
    fun theNativeBarChannelActionsFollowWebTaskV2OrderWithoutMembers() =
        runRobolectricComposeUiTest {
            val bridge = RaftNativeNavBarBridge()
            var actions: String? = null
            bridge.attachListener(
                object : RaftNativeNavBarListener {
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
                        actions = actionsJson
                    }

                    override fun onNativeNavBarCleared() = Unit

                    override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
                }
            )
            showHeader(
                replyCount = null,
                onOpenInChannel = {},
                nativeBar = bridge,
                headerPayload =
                    RaftThreadScreenPayload(
                        instanceId = "channel-general",
                        title = "general",
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        sourceThreadId = "channel-general",
                        membersCount = 28,
                    ),
                display =
                    ThreadHeaderDisplayModel(
                        title = "general",
                        subtitle = null,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                    ),
            )
            runOnIdle {
                val ids = Json.parseToJsonElement(actions!!).jsonArray.map { it.jsonObject["id"] }
                assertEquals(listOf(JsonPrimitive("thread-search"), JsonPrimitive("thread-settings")), ids, actions!!)
            }
        }
}
