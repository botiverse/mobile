package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentAppGrantRevokeFailure
import build.raft.app.data.AgentAppGrantRevokeOutcome
import build.raft.app.data.AgentAppGrantsRevokeStore
import build.raft.app.data.AgentAppLoginItem
import build.raft.app.data.AgentAppLoginLoadState
import build.raft.app.data.AgentAppLoginSnapshot
import build.raft.app.data.AgentAppLoginType
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The agent's Apps tab on a real composition (web AgentAppGrantsRow / RevokeAppGrantsDialog,
 * botiverse/slock 31abbdefb #13293): one row per App with its plain line; Revoke access and the
 * per-grant Revoke only for someone who may manage the agent's access (the details are readable by
 * all); the confirmation lists each grant, revokes them all, and the row leaves; a refusal says why
 * with its trace ID and leaves only Close. The list lives in a [SharedAgentDetailStore] as on the profile.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersAgentAppGrantsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun oneRowPerAppSaysWhatItCanDoAndAViewerWhoMayNotManageReadsDetailsWithoutRevoke() =
        runRobolectricComposeUiTest {
            val server = UiTestServer()
            setAppsTab(server, canManage = false)

            onAllNodesWithTag(membersAgentAppRowTag("stamp")).assertCountEquals(1)
            onAllNodesWithText("Stamp").assertCountEquals(1)
            onAllNodesWithTag(MembersAgentAppAbilitiesTag)[0].assertTextEquals("Stamp can sign in as this agent and send notifications")
            onAllNodesWithTag(membersAgentAppRevokeTag("stamp")).assertCountEquals(0)

            onNodeWithContentDescription("Details for Stamp").performClick()
            waitForIdle()
            onNodeWithTag(membersAgentAppGrantTag(STAMP_SIGN_IN.id)).assertExists()
            onNodeWithText("Granted when the agent signed in").assertExists()
            onNodeWithText("agent:notification:write").assertExists()
            onAllNodesWithTag(membersAgentAppRevokeGrantTag(STAMP_SIGN_IN.id)).assertCountEquals(0)
            onAllNodesWithTag(membersAgentAppDetailsTag("reminder")).assertCountEquals(0)
            assertEquals(emptyList(), server.posts)
        }

    @Test
    fun revokeAccessConfirmsListingEachGrantThenRevokesThemAllAndTheRowLeaves() =
        runRobolectricComposeUiTest {
            val server = UiTestServer()
            setAppsTab(server, canManage = true)

            onNodeWithContentDescription("Revoke access for Stamp").performClick()
            awaitConfirm()
            onNodeWithText("Revoke app access?").assertExists()
            onNodeWithTag(membersAgentAppRevokeItemTag(STAMP_SIGN_IN.id)).assertExists()
            onNodeWithTag(membersAgentAppRevokeItemTag(STAMP_NOTIFY.id)).assertExists()
            onNodeWithText("Sign in as this agent").assertExists()
            onNodeWithText("identity / openid / profile").assertExists()
            assertEquals(emptyList(), server.posts, "nothing before the confirmation")

            onNodeWithTag(MembersAgentAppRevokeConfirmTag).performClick()
            waitForIdle()

            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "one call per grant")
            onAllNodesWithTag(MembersAgentAppRevokeConfirmTag).assertCountEquals(0)
            onAllNodesWithTag(membersAgentAppRowTag("stamp")).assertCountEquals(0)
            onAllNodesWithTag(membersAgentAppRowTag("reminder")).assertCountEquals(1)
        }

    @Test
    fun aRefusedGrantSaysWhyWithItsTraceIdAndLeavesOnlyClose() =
        runRobolectricComposeUiTest {
            val server = UiTestServer(refused = setOf(STAMP_SIGN_IN.id))
            setAppsTab(server, canManage = true)

            onNodeWithContentDescription("Revoke access for Stamp").performClick()
            awaitConfirm()
            onNodeWithTag(MembersAgentAppRevokeConfirmTag).performClick()
            waitForIdle()

            onNodeWithTag(MembersAgentAppRevokePartialFailureTag).assertExists()
            onNodeWithText("Not allowed").assertExists()
            onNodeWithText("You don't have permission to revoke this, so trying again won't help.").assertExists()
            onNodeWithText("The server said: Only server owners and admins can manage integrations").assertExists()
            onNodeWithTag(RaftTraceIdNoteTestTag).assertExists()
            onNodeWithTag(MembersAgentAppRevokeConfirmTag).assertTextEquals("Close")
            onAllNodesWithText("Cancel").assertCountEquals(0)

            onNodeWithTag(MembersAgentAppRevokeConfirmTag).performClick()
            waitForIdle()
            onAllNodesWithTag(MembersAgentAppRevokeConfirmTag).assertCountEquals(0)
            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "Close revokes nothing more")
            onAllNodesWithTag(membersAgentAppRowTag("stamp")).assertCountEquals(1)
        }

    private fun ComposeUiTest.setAppsTab(server: UiTestServer, canManage: Boolean) {
        val detailStore =
            SharedAgentDetailStore().apply {
                dispatch(SharedAgentDetailIntent.SelectMember(AGENT))
                dispatch(
                    SharedAgentDetailIntent.SetDetail(
                        AGENT,
                        AgentDetailSnapshot(
                            agentId = AGENT,
                            directMessages = emptyList(),
                            reminders = emptyList(),
                            activity = emptyList(),
                            appLogins = AgentAppLoginSnapshot(agentId = AGENT, items = server.grants)
                        )
                    )
                )
            }
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                AppsTabUnderTest(server, detailStore, canManage)
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.awaitConfirm() {
        try {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MembersAgentAppRevokeConfirmTag).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            error("waited for the revoke confirmation: ${timeout.message}")
        }
    }
}

@Composable
private fun AppsTabUnderTest(server: UiTestServer, detailStore: SharedAgentDetailStore, canManage: Boolean) {
    val scope = rememberCoroutineScope()
    val store =
        remember(scope) {
            AgentAppGrantsRevokeStore(
                revokeGrant = server::revoke,
                loadAppLogins = server::load,
                applyToDetail = detailStore::dispatch,
                scope = scope,
            )
        }
    val state by detailStore.state.collectAsState()
    MembersAgentAppsTab(
        agentId = AGENT,
        detail = state.detail,
        timeContext = RaftTimeContext(localOffsetMinutes = 0),
        onOpenUrl = {},
        appEventsLoader = null,
        appEventLoader = null,
        appAccess = MembersAgentAppAccessBinding(store = store, canManage = { canManage })
    )
}

/** The agent's grants on the server; a revoke lands unless it is [refused] (403). */
private class UiTestServer(private val refused: Set<String> = emptySet()) {
    var grants: List<AgentAppLoginItem> = listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER)
    val posts = mutableListOf<String>()

    suspend fun revoke(grantId: String): AgentAppGrantRevokeOutcome {
        posts += grantId
        if (grantId in refused) {
            return AgentAppGrantRevokeOutcome.NotRevoked(
                AgentAppGrantRevokeFailure.Refused("Only server owners and admins can manage integrations", TRACE_ID)
            )
        }
        grants = grants.filterNot { it.id == grantId }
        return AgentAppGrantRevokeOutcome.Revoked
    }

    suspend fun load(agentId: MemberId): DataResult<AgentAppLoginSnapshot> =
        DataResult.Content(
            AgentAppLoginSnapshot(
                agentId = agentId,
                items = grants,
                loadState = if (grants.isEmpty()) AgentAppLoginLoadState.Empty else AgentAppLoginLoadState.Content
            )
        )
}

private val AGENT = MemberId("agent-1")
private const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"

private fun grant(id: String, clientId: String, clientName: String, scopes: List<String>, grantSource: String) =
    AgentAppLoginItem(
        id = id,
        type = AgentAppLoginType.Active,
        clientId = clientId,
        clientName = clientName,
        scopes = scopes,
        createdAt = "2026-10-05T01:00:00.000Z",
        grantSource = grantSource
    )

private val STAMP_SIGN_IN = grant("g-stamp-login", "stamp", "Stamp", listOf("identity", "openid", "profile"), "agent_login")
private val STAMP_NOTIFY = grant("g-stamp-notify", "stamp", "Stamp", listOf("agent:notification:write"), "app_request")
private val REMINDER = grant("g-reminder", "reminder", "Reminder App", listOf("agent:notification:write"), "person")
