package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands 11c0ed95: agent profiles share the shell's one agent-detail store, and a profile kept
 * composed under another agent's profile found the store holding that agent when it was uncovered
 * (A's profile → its computer → D's profile → Back → Back: A stuck on "Loading…" for good).
 *
 * Each profile here is composed the way `MemberDetailPageContent` uses
 * [MemberDetailAgentStoreReclaimEffect]: its load (keyed by a refresh key) deselects and selects its
 * agent, it paints its agent only while the store holds it, and a reclaim bumps the refresh key.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemberDetailAgentStoreReclaimUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val store = SharedAgentDetailStore(logger = {})
    private val loads = mutableMapOf<MemberId, Int>()

    private fun selectInStore(agentId: MemberId) {
        store.dispatch(SharedAgentDetailIntent.DeselectMember)
        store.dispatch(SharedAgentDetailIntent.SelectMember(agentId))
        store.dispatch(
            SharedAgentDetailIntent.SetDetail(
                agentId = agentId,
                detail = AgentDetailSnapshot(
                    agentId = agentId,
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = emptyList()
                )
            )
        )
    }

    @Composable
    private fun Profile(agentId: MemberId, visible: Boolean) {
        var refreshKey by remember { mutableStateOf(0) }
        var routeIsAgent by remember { mutableStateOf(false) }
        LaunchedEffect(refreshKey) {
            selectInStore(agentId)
            loads[agentId] = (loads[agentId] ?: 0) + 1
            routeIsAgent = true
        }
        MemberDetailAgentStoreReclaimEffect(
            store = store,
            agentId = agentId,
            visible = visible,
            routeIsAgent = routeIsAgent,
            onReclaim = { refreshKey += 1 },
        )
        val state by store.state.collectAsState()
        val holdsThisAgent = state.agentId == agentId && state.detail?.agentId == agentId
        Text(if (holdsThisAgent) "profile ${agentId.value}" else "loading ${agentId.value}")
    }

    @Test
    fun uncoveredProfileTakesTheStoreBackFromAnotherAgent() =
        runRobolectricComposeUiTest {
            var aVisible by mutableStateOf(true)
            setContent { Profile(A, visible = aVisible) }
            onNodeWithText("profile a").assertExists()

            // Covered; D's profile (opened over it) takes the store.
            runOnIdle { aVisible = false }
            runOnIdle { selectInStore(D) }
            onNodeWithText("loading a").assertExists()
            assertEquals(1, loads[A], "a covered profile leaves the store alone")

            runOnIdle { aVisible = true }
            waitForIdle()
            onNodeWithText("profile a").assertExists()
            assertEquals(2, loads[A])
            assertEquals(A, store.state.value.agentId)
        }

    @Test
    fun backThroughAnotherAgentsProfileShowsTheFirstAgentAgain() =
        runRobolectricComposeUiTest {
            var aVisible by mutableStateOf(true)
            var dOpen by mutableStateOf(false)
            setContent {
                Column {
                    Profile(A, visible = aVisible)
                    if (dOpen) Profile(D, visible = true)
                }
            }
            onNodeWithText("profile a").assertExists()

            // A → (its computer) → D's profile: A covered, D on top.
            runOnIdle { aVisible = false }
            runOnIdle { dOpen = true }
            onNodeWithText("profile d").assertExists()
            onNodeWithText("loading a").assertExists()

            // Back, Back.
            runOnIdle { dOpen = false }
            runOnIdle { aVisible = true }
            waitForIdle()
            onNodeWithText("profile a").assertExists()
            assertEquals(mapOf(A to 2, D to 1), loads.toMap())
        }

    @Test
    fun twoVisibleProfilesDoNotTakeTurnsAndTheSurvivorTakesTheStoreBack() =
        runRobolectricComposeUiTest {
            var dOpen by mutableStateOf(false)
            setContent {
                Column {
                    Profile(A, visible = true)
                    if (dOpen) Profile(D, visible = true)
                }
            }
            onNodeWithText("profile a").assertExists()

            // The wide layout: D's profile opens next to A's, both on screen. D loaded last and keeps
            // the store; A does not take it back while D shows it.
            runOnIdle { dOpen = true }
            waitForIdle()
            onNodeWithText("profile d").assertExists()
            onNodeWithText("loading a").assertExists()
            assertEquals(mapOf(A to 1, D to 1), loads.toMap())

            // D's profile closes: A takes the store back once.
            runOnIdle { dOpen = false }
            waitForIdle()
            onNodeWithText("profile a").assertExists()
            assertEquals(mapOf(A to 2, D to 1), loads.toMap())
        }

    @Test
    fun aVisibleProfileLeavesAnAgentSeededForAProfileAboutToOpen() =
        runRobolectricComposeUiTest {
            setContent { Profile(A, visible = true) }
            onNodeWithText("profile a").assertExists()

            // A row tap seeds D before D's profile appears: A must not race it for the store.
            runOnIdle { selectInStore(D) }
            waitForIdle()
            assertEquals(D, store.state.value.agentId)
            assertEquals(1, loads[A])
        }

    private companion object {
        val A = MemberId("a")
        val D = MemberId("d")
    }
}
