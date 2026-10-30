package build.raft.app.assistant

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftRobolectricScreenshot
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAssistantOtterHosts
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import androidx.compose.foundation.layout.Box
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.pager.RaftPagerHost
import kotlinx.serialization.json.JsonObject

/**
 * The assistant's panel open over a page (web AssistantOtter's phone Drawer with
 * AssistantOtterPanel): the drawer window, 82% of the window tall over the scrim; the panel head
 * (the otter's head, the name, the meta line, collapse) and the DM under it. The DM is the real
 * conversation page (SharedThreadRouteContent in its own pager, as RaftChildPage mounts
 * THREAD_PAGE) fed by the thread visual fixtures' data path ([AssistantDmFixturePage]): a brief
 * with a reaction and a reply thread, the owner's answer, the assistant's reply, and the
 * composer. Inside the panel the page has no head and no tabs. Light, dark, and paused (the chip
 * and the sleeping mood's reading line).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadAssistantPanelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun openLight() = capture("assistant.panel.open", RaftAppAppearance.Light, paused = false)

    @Test
    fun openDark() = capture("assistant.panel.open.dark", RaftAppAppearance.Dark, paused = false)

    @Test
    fun pausedLight() = capture("assistant.panel.paused", RaftAppAppearance.Light, paused = true)

    private fun capture(name: String, appearance: RaftAppAppearance, paused: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = {
                waitForTheDm()
                dialogFittedToItsWindow()
            },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val otter =
                remember {
                    ThreadAssistantOtter(
                        hostId = "channel",
                        hosts = RaftAssistantOtterHosts(),
                        mood = if (paused) RaftOtterMood.Away else RaftOtterMood.Idle,
                        scale = RaftAssistantOtterDefaults.PhoneScale,
                        endInset = RaftAssistantOtterDefaults.PhoneEndInset,
                        onOpen = {},
                        assistantName = "Otto",
                        agentId = AssistantDmFixtureAgentId,
                        serverId = "server-1",
                        dmChannelId = AssistantDmFixtureChannelId,
                        readsDms = true,
                        paused = paused,
                        panelOpen = true,
                        panelEmbed = ThreadAssistantPanelEmbed(AssistantDmFixtureAgentId) {},
                    )
                }
            val host =
                remember {
                    val transport = HostBridgeTransport { _, _, _ -> }
                    val navigator =
                        object : RaftCmpNavigator {
                            override fun openPage(pageName: String, pageData: JsonObject) = Unit

                            override fun closePage() = Unit
                        }
                    RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftHost provides host) {
                RaftDesignSystemTheme {
                    ThreadAssistantPanelSheet(otter) {
                        // As RaftChildPage mounts THREAD_PAGE in production: the page in its own pager.
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            RaftPagerHost(factory = { AssistantDmFixturePage() }, args = remember { assistantDmFixturePageArgs() })
                        }
                    }
                }
            }
        }

    /**
     * The page loads its messages off the main thread (the data source's coroutines): give it real
     * time as well as the main looper's virtual time before the capture.
     */
    private fun waitForTheDm() {
        repeat(40) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    /** The drawer's window, its compose content measured to it (see TaskCreateFromMessageDialogScreenshotTest). */
    private fun dialogFittedToItsWindow(): android.view.View? {
        val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
        val content = findCompose(decor) ?: return decor
        val parent = content.parent as? android.view.View ?: return decor
        val exactly = android.view.View.MeasureSpec.EXACTLY
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
            android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
        )
        content.layout(0, 0, parent.width, parent.height)
        return decor
    }

    private fun findCompose(view: android.view.View): android.view.View? {
        if (view is androidx.compose.ui.platform.ViewRootForTest) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
