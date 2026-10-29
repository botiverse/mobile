package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Forwarded to #x." receipt after a single forward, light and dark, as it floats over a
 * channel header (cindyz 10-29: the old soft-fill Banner let the header show through in dark
 * mode). Web: slock `ForwardToastProvider` (raft-ui Toast, top centre, title + "View in chat").
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class ThreadForwardToastScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "forward-toast-screenshot"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun overHeaderLight() = capture("thread.forward.toast", RaftAppAppearance.Light)

    @Test
    fun overHeaderDark() = capture("thread.forward.toast.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadHeaderMenuEventBus provides ForwardToastQuietEventBus,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        ChannelHeaderUnderToast()
                        ThreadForwardSuccessToastView(banner = forwardBanner, onViewInChat = {}, onDismiss = {})
                    }
                }
            }
        }
}

private val forwardBanner =
    ThreadForwardSuccessBanner(
        target =
            ForwardMessageTarget(
                serverId = ServerId("server-a"),
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "proj-docs",
                title = "#proj-docs",
                channelId = ChannelId("channel-proj-docs"),
            ),
        destination = ForwardMessageDestination(ServerId("server-a"), ChannelId("channel-proj-docs"), ConversationKind.Channel),
        messageId = MessageId("forwarded"),
    )

/** The page under the toast: the channel header (title, topic, Search, Settings) and a message line. */
@Composable
private fun ChannelHeaderUnderToast() {
    Column {
        ThreadPageHeader(
            payload =
                RaftThreadScreenPayload(
                    instanceId = "forward-toast-channel",
                    sourceThreadId = "channel-wg-rbac",
                    title = "#wg-rbac",
                    subtitle = "RBAC working group: roles and permissions",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    selectedTab = "chat",
                    showModeTabs = true,
                    showSettingsAction = true,
                    showMembersAction = false,
                    showOpenInChannelAction = false,
                ),
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        RaftText(
            "These are public docs: agents and people follow them, so a wrong permission is worse than none.",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = RaftTheme.typography.body,
        )
    }
}

/** The header menu's bus: nothing is opened in a still capture. */
private object ForwardToastQuietEventBus : build.raft.app.data.RaftEventBus {
    override fun observe(eventName: String, crossProcess: Boolean, listener: (kotlinx.serialization.json.JsonObject?) -> Unit) =
        build.raft.app.data.RealtimeSubscription {}

    override fun post(eventName: String, eventData: kotlinx.serialization.json.JsonObject, crossProcess: Boolean, source: String) = Unit
}
