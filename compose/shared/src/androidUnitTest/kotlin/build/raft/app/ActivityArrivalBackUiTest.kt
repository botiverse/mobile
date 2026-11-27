package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import build.raft.app.shell.RaftAdaptiveListDetailSplit
import build.raft.app.shell.RaftListPaneWidths
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * xxchan, mobile #193: on the wide layout with the Activity list collapsed, Back on an open
 * conversation closed it, the list came back into composition beside the empty detail, claimed a
 * fresh arrival visit and reopened the same conversation (with its focused-message highlight), so
 * every Back bounced straight back into the chat. The visit is the Activity surface's
 * ([rememberActivityArrivalVisits]), not the list's: the list leaving and re-entering composition is
 * not a new visit; leaving the surface is.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ActivityArrivalBackUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun backThatClosesTheConversationBesideACollapsedListDoesNotReopenIt() =
        runRobolectricComposeUiTest {
            var surfaceActive by mutableStateOf(true)
            var hasSelection by mutableStateOf(false)
            var reopens = 0
            setContent {
                val widths = remember { RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())) }
                // As the shell's Activity tab content: the visits above the split.
                val visits = rememberActivityArrivalVisits(surfaceActive)
                RaftAdaptiveListDetailSplit(
                    kind = RaftListPaneKind.List,
                    widths = widths,
                    dualPane = true,
                    active = surfaceActive,
                    hasSelection = hasSelection,
                    detailBottomInset = 0.dp,
                    listPane = { listActive ->
                        // As SharedHomeActivityScreen: its server's visit, claimed once the list loaded.
                        val visit = remember(visits) { visits.of("server-1") }
                        ActivityArrivalReopenEffect(visit = visit, active = listActive, loaded = true) {
                            if (!hasSelection) {
                                reopens += 1
                                hasSelection = true
                            }
                        }
                        Box(Modifier.fillMaxSize().testTag(LIST))
                    },
                    detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                    collapsed = true,
                    onCollapsedChange = {},
                )
            }
            // Arrival: nothing open, the collapsed list shows, reopens the last conversation, then hides.
            waitForIdle()
            assertEquals(1, reopens, "the arrival reopens the conversation opened last time")
            onNodeWithTag(LIST).assertDoesNotExist()

            // Back closes the conversation: the list comes back and the detail stays closed.
            hasSelection = false
            waitForIdle()
            onNodeWithTag(LIST).assertExists()
            assertEquals(1, reopens, "Back must not reopen the conversation it closed")
            assertEquals(false, hasSelection, "the detail stays closed after Back")

            // Leaving Activity ends the visit; coming back is a new arrival.
            surfaceActive = false
            waitForIdle()
            surfaceActive = true
            waitForIdle()
            assertEquals(2, reopens, "the next visit reopens again")
            onNodeWithTag(LIST).assertDoesNotExist()
        }

    private companion object {
        const val LIST = "arrival-list"
        const val DETAIL = "arrival-detail"
    }
}
