package build.raft.app.compat.pager

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bug A (11500008): the wide detail pane is one `RaftChildPage(THREAD_PAGE, pageData)` whose
 * pageData changes when the user taps another conversation. [RaftPagerHost] then builds a new
 * pager; its content must start from a fresh composition. Pages read their launch params into
 * unkeyed `remember` (ThreadPageScreen's payload / instanceId / adaptive close request), so
 * running the new pager's content in the old slot table kept showing the previous conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftPagerHostPageSwapTest {
    private val rendered = mutableListOf<String>()

    /** Mirrors ThreadPageScreen: launch params read once into an unkeyed remember. */
    private inner class ProbePage : ComposeContainer() {
        override fun willInit() {
            setContent {
                val pager = LocalActivity.current.getPager() as Pager
                val conversation = remember { pager.pageData.params.optString("conversation") }
                SideEffect { rendered += conversation }
            }
        }
    }

    private fun args(conversation: String) =
        RaftCmpPageArgs("probe", JsonObject(mapOf("conversation" to JsonPrimitive(conversation))))

    @Test
    fun newPageArgsRenderTheNewPageNotThePreviousPagesState() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // Page hosts read moko strings / preferences through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        var current by mutableStateOf(args("channel-all"))
        activity.setContent {
            CompositionLocalProvider(LocalRaftHost provides host) {
                RaftPagerHost(factory = { ProbePage() }, args = current)
            }
        }
        settle()
        assertEquals("channel-all", rendered.lastOrNull(), "first page, rendered=$rendered")

        // The list tap: same host slot, new page args.
        current = args("channel-push-test")
        settle()
        assertEquals("channel-push-test", rendered.lastOrNull(), "after switching, rendered=$rendered")
    }

    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }
}
