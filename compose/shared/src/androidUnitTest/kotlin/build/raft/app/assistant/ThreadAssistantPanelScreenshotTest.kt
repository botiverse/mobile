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
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import build.raft.app.LocalThreadReactionAtlasImage
import build.raft.app.resources.RaftBundledFiles
import build.raft.app.resources.Res
import kotlinx.coroutines.runBlocking
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

    /**
     * RaftPagerHost installs Raft's Coil loader; another test in this JVM may already have made
     * Coil's default singleton. Installing it first (and tolerating that) marks it installed, as
     * the other screenshot tests with images do.
     */
    @org.junit.Before
    fun resetFixture() {
        AssistantDmFixtureNetworkApi.latestServed = false
    }

    @org.junit.Before
    fun installImageLoader() {
        runCatching { build.raft.app.image.raftInstallImageLoader() }
    }

    /**
     * The conversation page builds the process's shared store provider, which reads app-scope
     * key-value stores (MMKV in the app): in-memory ones here.
     */
    @org.junit.Before
    fun installKeyValueStores() {
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

    /** Later tests in this JVM expect no provider and no app-scope stores (RaftEnvironmentIndicatorTest). */
    @org.junit.After
    fun dropSharedStores() {
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

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
            val atlas =
                remember {
                    val bytes = runBlocking { Res.readBytes(RaftBundledFiles.reactionAtlasV1.path) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }
            val host =
                remember {
                    val transport =
                        HostBridgeTransport { method, params, _ ->
                            // TEMP diagnostics: the page's bridge logs (thread_* lines).
                            if (method.contains("log", ignoreCase = true) && (params.contains("thread") || params.contains("repl"))) {
                                println("assistant_dm_bridge $method ${params.take(400)}")
                            }
                        }
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
                            // The reaction atlas decoded here, synchronously (as
                            // ThreadMessageFooterScreenshotTest): the page's provider reuses it
                            // instead of an async load that may not have finished by the capture.
                            CompositionLocalProvider(LocalThreadReactionAtlasImage provides atlas) {
                                RaftPagerHost(factory = { AssistantDmFixturePage() }, args = remember { assistantDmFixturePageArgs() })
                            }
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
        repeat(200) {
            if (AssistantDmFixtureNetworkApi.latestServed) return@repeat
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        // Then the parse (off the main thread) and the list's layout: a fixed number of rounds.
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
