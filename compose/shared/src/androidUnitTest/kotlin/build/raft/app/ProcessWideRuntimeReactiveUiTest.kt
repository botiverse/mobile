package build.raft.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.ChannelId
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that replacing process-wide Compose snapshot states with [kotlinx.coroutines.flow.MutableStateFlow]
 * + `@Composable observe*()` preserves live recomposition for every converted runtime in `#proj-mobile:#264`,
 * including switching keyed observers (`channelId` / `windowId`) without leaking stale values.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessWideRuntimeReactiveUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class MemoryPreferenceStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @Test
    fun displayPreferencesRecomposeLiveOnUpdate() =
        runRobolectricComposeUiTest {
            RaftDisplayPreferenceRuntime.resetForTest(MemoryPreferenceStore())
            try {
                setContent {
                    val live = RaftDisplayPreferenceRuntime.observeShowLiveAgentActivity()
                    val model = RaftDisplayPreferenceRuntime.observeShowAgentModelName()
                    val desc = RaftDisplayPreferenceRuntime.observeShowSenderDescription()
                    val hide = RaftDisplayPreferenceRuntime.observeHideEmptySidebarSections()
                    BasicText("$live|$model|$desc|$hide", Modifier.testTag("prefs"))
                }
                waitForIdle()
                onNodeWithTag("prefs").assertTextEquals("true|true|true|${build.raft.app.data.RaftSidebarHideEmptySectionsDefault}")

                RaftDisplayPreferenceRuntime.setShowLiveAgentActivity(false)
                RaftDisplayPreferenceRuntime.setShowAgentModelName(false)
                RaftDisplayPreferenceRuntime.setShowSenderDescription(false)
                RaftDisplayPreferenceRuntime.setHideEmptySidebarSections(!build.raft.app.data.RaftSidebarHideEmptySectionsDefault)
                waitForIdle()
                onNodeWithTag("prefs").assertTextEquals("false|false|false|${!build.raft.app.data.RaftSidebarHideEmptySectionsDefault}")
            } finally {
                RaftDisplayPreferenceRuntime.resetForTest(null)
            }
        }

    @Test
    fun keyedChannelJoinAndWindowInsetObserversUpdateAndSwitchKeysCleanly() =
        runRobolectricComposeUiTest {
            val chA = ChannelId("ch-reactive-a")
            val chB = ChannelId("ch-reactive-b")
            val winA = "win-reactive-a"
            val winB = "win-reactive-b"
            var activeChannel by mutableStateOf(chA)
            var activeWindow by mutableStateOf(winA)
            try {
                setContent {
                    val joining = ThreadChannelJoinsInFlight.observeIsJoining(activeChannel)
                    val topPx = RaftHostWindowInsets.observe(activeWindow)?.topPx?.toInt() ?: -1
                    BasicText("$joining|$topPx", Modifier.testTag("keyed"))
                }
                waitForIdle()
                onNodeWithTag("keyed").assertTextEquals("false|-1")

                ThreadChannelJoinsInFlight.begin(chA)
                RaftHostWindowInsets.publish(winA, topPx = 42f, bottomPx = 10f, statusBarHidden = false)
                waitForIdle()
                onNodeWithTag("keyed").assertTextEquals("true|42")

                // Switching keys must immediately reflect the target key's live state (not leak chA/winA).
                RaftHostWindowInsets.publish(winB, topPx = 99f, bottomPx = 0f, statusBarHidden = false)
                activeChannel = chB
                activeWindow = winB
                waitForIdle()
                onNodeWithTag("keyed").assertTextEquals("false|99")

                ThreadChannelJoinsInFlight.begin(chB)
                RaftHostWindowInsets.remove(winB)
                waitForIdle()
                onNodeWithTag("keyed").assertTextEquals("true|-1")

                // Whitespace/blank windowId normalization matches publish/remove symmetrically.
                RaftHostWindowInsets.publish("  ", topPx = 77f, bottomPx = 0f, statusBarHidden = false)
                activeWindow = "   "
                waitForIdle()
                onNodeWithTag("keyed").assertTextEquals("true|77")
            } finally {
                ThreadChannelJoinsInFlight.end(chA)
                ThreadChannelJoinsInFlight.end(chB)
                RaftHostWindowInsets.remove(winA)
                RaftHostWindowInsets.remove(winB)
                RaftHostWindowInsets.remove("")
            }
        }
}
