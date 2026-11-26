package build.raft.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.cmp.RaftCmpToastLayer
import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHostState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for Hands `9e0a70ff` (iPad 1.30.0 cold-start phone-layout bug):
 * when `RaftCmpToastLayer`'s `externalWindow` transitions from null (Frame 0 before the scene
 * attaches under SwiftUI `WindowGroup`) to a scene-level `RaftCmpToastWindow` (on
 * `viewWillAppear`), `content()` must stay mounted in the same structural Compose slot without
 * disposing or recreating the page (`RaftPagerHost`) or losing remembered state.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftCmpToastLayerStabilityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val attachedWindows = mutableListOf<RaftCmpToastWindow>()

    @AfterTest
    fun tearDown() {
        attachedWindows.forEach { it.detach() }
        attachedWindows.clear()
    }

    @Test
    fun lateBindingExternalWindowKeepsContentMountedAndRebindsToastOrigin() = runRobolectricComposeUiTest {
        val origin = BridgeWireHostCapabilities(HostBridgeTransport { _, _, _ -> })
        var externalWindow by mutableStateOf<RaftCmpToastWindow?>(null)
        var contentMounts = 0
        var contentDisposals = 0
        var rememberedToken: Any? = null
        var observedToastState: RaftToastHostState? = null

        setContent {
            RaftCmpToastLayer(origin = origin, externalWindow = externalWindow) {
                val token = remember { Any() }
                rememberedToken = token
                observedToastState = LocalRaftToastHostState.current
                DisposableEffect(Unit) {
                    contentMounts += 1
                    onDispose { contentDisposals += 1 }
                }
            }
        }
        waitForIdle()

        val initialToken = rememberedToken
        val initialToastState = observedToastState
        assertEquals(1, contentMounts, "content mounts once on Frame 0")
        assertEquals(0, contentDisposals, "content has not been disposed on Frame 0")
        assertTrue(initialToken != null && initialToastState != null)

        // Post a toast on Frame 0 while the fallback layer is active, then attach the scene overlay.
        assertTrue(RaftCmpToasts.post("Cold-start toast", origin))
        val sceneState = RaftToastHostState()
        val sceneWindow = RaftCmpToasts.attach(sceneState).also {
            it.setLifecycle(visible = true, resumed = true)
            attachedWindows += it
        }
        externalWindow = sceneWindow
        waitForIdle()

        assertEquals(1, contentMounts, "attaching externalWindow must NOT recreate content/RaftPagerHost")
        assertEquals(0, contentDisposals, "attaching externalWindow must NOT dispose content/RaftPagerHost")
        assertSame(initialToken, rememberedToken, "remembered subtree state must survive externalWindow attach")
        assertSame(sceneState, observedToastState, "LocalRaftToastHostState must switch to the scene window")
        assertEquals(
            listOf("Cold-start toast"),
            sceneState.visible.map { it.title },
            "fallback window detaching must hand off any Frame-0 toast to the scene window",
        )

        // Subsequent toasts route directly into the scene window via the rebound origin.
        assertTrue(RaftCmpToasts.post("After attach", origin))
        assertEquals(listOf("After attach"), sceneState.visible.map { it.title })
    }
}
