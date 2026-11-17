package build.raft.app.compat.pager

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * artin (Compose 版, wide layout): a reply thread opened again from Activity stopped at his own last
 * message; the agents' later replies never appeared. The detail pane is a child page whose
 * `viewModel(key = "shared-thread-<conversation>")` resolved against the hosting window's store, so
 * reopening a conversation handed back the view model of the earlier visit: already started (no
 * refresh) and without realtime while it was off screen. Each pager now owns its view models, as each
 * Kuikly page did: a new page for a conversation gets a new view model, and a destroyed page's are cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftPagerHostViewModelStoreTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class ProbeViewModel(val conversation: String) : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val rendered = mutableListOf<ProbeViewModel>()

    /** Mirrors ThreadPageScreen: one view model per conversation, keyed by the conversation id. */
    private inner class ProbePage : ComposeContainer() {
        override fun willInit() {
            setContent {
                val pager = LocalActivity.current.getPager() as Pager
                val conversation = remember { pager.pageData.params.optString("conversation") }
                val model = viewModel(key = "shared-thread-$conversation") { ProbeViewModel(conversation) }
                SideEffect { if (rendered.lastOrNull() !== model) rendered += model }
            }
        }
    }

    private fun args(conversation: String, generation: Int) =
        RaftCmpPageArgs(
            "probe",
            JsonObject(
                mapOf(
                    "conversation" to JsonPrimitive(conversation),
                    "generation" to JsonPrimitive(generation),
                )
            ),
        )

    @Test
    fun reopeningAConversationInTheDetailPaneGetsAFreshViewModel() {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        var current by mutableStateOf(args("thread-a", generation = 1))
        activity.setContent {
            CompositionLocalProvider(LocalRaftHost provides host) {
                RaftPagerHost(factory = { ProbePage() }, args = current)
            }
        }
        settleUntil { rendered.lastOrNull()?.conversation == "thread-a" }
        val firstVisit = rendered.last()

        // Another Activity row, then the first conversation again: two new pages in the same pane.
        current = args("thread-b", generation = 2)
        settleUntil { rendered.lastOrNull()?.conversation == "thread-b" }
        current = args("thread-a", generation = 3)
        settleUntil { rendered.lastOrNull()?.conversation == "thread-a" && rendered.size >= 3 }

        val secondVisit = rendered.last()
        assertEquals(listOf("thread-a", "thread-b", "thread-a"), rendered.map { it.conversation })
        assertNotSame(firstVisit, secondVisit, "the reopened conversation reused the earlier visit's view model")
        assertTrue(firstVisit.cleared, "the closed page's view model was never cleared")
        assertTrue(rendered[1].cleared, "the closed page's view model was never cleared")
    }

    private fun settleUntil(done: () -> Boolean) {
        repeat(50) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }
}
