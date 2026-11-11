package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A reply thread's header on a real composition, against the web ThreadPanelHeader
 * (`thread-header-channel-link` / `thread-header-reply-count`): the conversation the thread belongs
 * to, a link that opens the parent message there, then " · N replies". The Compose head and the
 * iOS native bar (mobile_ios_native_nav_v1) carry the same line and the same tap.
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
                            payload = payload,
                            headerDisplay =
                                ThreadHeaderDisplayModel(
                                    title = ThreadReplyPageTitle,
                                    subtitle = "@Lagoon",
                                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                    channelKind = "dm",
                                ),
                            originMessage = origin,
                            originReplyCount = replyCount,
                            applyTopSafeArea = false,
                            onBack = {},
                            onOpenInChannel = onOpenInChannel,
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
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
            onNodeWithText("@artin: 1.23 why").assertExists()
            onNodeWithText(" · 3 replies").assertExists()
            onNodeWithText("@Lagoon").performClick()
            runOnIdle { assertEquals(1, opens) }
        }

    @Test
    fun aThreadWithNoRepliesSaysSoOnceKnown() =
        runRobolectricComposeUiTest {
            showHeader(replyCount = 0, onOpenInChannel = {})
            onNodeWithText(" · 0 replies").assertExists()
        }

    @Test
    fun theNativeBarSubtitleCarriesTheCountAndOpensTheParentMessage() =
        runRobolectricComposeUiTest {
            val bridge = RaftNativeNavBarBridge()
            var publishedSubtitle: String? = null
            var extras: String? = null
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
                        publishedSubtitle = subtitle
                        extras = extrasJson
                    }

                    override fun onNativeNavBarCleared() = Unit

                    override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
                }
            )
            var opens = 0
            showHeader(replyCount = 12, onOpenInChannel = { opens++ }, nativeBar = bridge)
            runOnIdle {
                assertEquals("@Lagoon · 12 replies", publishedSubtitle)
                assertEquals(JsonPrimitive(true), Json.parseToJsonElement(extras!!).jsonObject["subtitleTap"])
                assertEquals(true, bridge.actionTapped(RaftNativeNavBarSubtitleId))
                assertEquals(1, opens)
            }
        }
}
