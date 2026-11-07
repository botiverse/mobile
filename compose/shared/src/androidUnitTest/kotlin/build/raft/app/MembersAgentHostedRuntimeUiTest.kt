package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentHostedRuntimeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.model.MemberId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a person lands on after creating a cloud agent: the wire record from a real agent response,
 * the section the profile picks for it, and the failed setup with its retry on a real composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentHostedRuntimeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val unused =
        Proxy.newProxyInstance(RaftAgentsApi::class.java.classLoader, arrayOf(RaftAgentsApi::class.java)) { _, method, _ ->
            error("unused: ${method.name}")
        } as RaftAgentsApi

    private inner class FakeApi : RaftAgentsApi by unused {
        val retryBodies = mutableListOf<String>()
        var status = """{"setupState":"credential_minted"}"""
        var retryResponse = HomeNetworkResponse(code = 200, bodyText = """{"hostedRuntime":null}""")

        override suspend fun createAgent(body: String) =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"id":"a1","name":"cloud-one","runtime":"external","hostedRuntime":{"provider":"antiproton","state":"failed",""" +
                        """"syncPending":false,"push":null,"lastError":{"code":"provider_refused","message":"No capacity","httpStatus":503,"at":"2026-11-06T00:00:00Z"},""" +
                        """"attemptCount":1,"retryNeedsModel":false}}"""
            )

        override suspend fun agentExternalStatus(agentId: MemberId) = HomeNetworkResponse(code = 200, bodyText = status)

        override suspend fun retryHostedRuntime(agentId: MemberId, body: String): HomeNetworkResponse {
            retryBodies += body
            return retryResponse
        }
    }

    private val failed =
        AgentHostedRuntimeSnapshot(state = "failed", lastErrorCode = "provider_refused", lastErrorMessage = "No capacity")

    @Test
    fun aCreatedCloudAgentLandsOnItsStateInsteadOfTheSetupSteps() = runPortableCoroutineTest {
        val created = assertIs<DataResult.Content<build.raft.app.data.AgentSummary>>(RaftAgentsApiClient(FakeApi()).createAgent("{}")).data
        val summary = AgentDetailSummary(id = created.id, name = created.name, hostedRuntime = created.hostedRuntime)
        assertEquals(failed, summary.hostedRuntime)
        assertEquals(MembersExternalAgentSection.Hosted, membersExternalAgentSection(summary))
        assertEquals(MembersExternalAgentSection.Setup, membersExternalAgentSection(summary.copy(hostedRuntime = null)))
        assertEquals(MembersExternalAgentSection.NotKnownYet, membersExternalAgentSection(null))
    }

    @Test
    fun hostedRuntimeStatesFollowWeb() {
        fun view(state: String, syncPending: Boolean = false, error: String? = null) =
            describeHostedRuntime(AgentHostedRuntimeSnapshot(state, syncPending = syncPending, lastErrorCode = error))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Provisioning, inProgress = true, canRetry = false), view("provisioning"))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Provisioning, inProgress = true, canRetry = true), view("provisioning", error = "e"))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Active, inProgress = false, canRetry = false), view("active"))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Syncing, inProgress = true, canRetry = false), view("active", syncPending = true))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Failed, inProgress = false, canRetry = true), view("failed"))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Deleting, inProgress = true, canRetry = false), view("deleting"))
        assertEquals(HostedRuntimeView(HostedRuntimeState.Deleted, inProgress = false, canRetry = false), view("deleted"))
        assertNull(view("from-the-future").state)
    }

    @Test
    fun aFailedSetupShowsWhyAndRetryMovesItOn() = runRobolectricComposeUiTest {
        val api = FakeApi()
        api.status = """{"setupState":"credential_minted","hostedRuntime":{"state":"active","syncPending":false}}"""
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersAgentHostedRuntimeSection(MemberId("a1"), failed, RaftAgentsApiClient(api))
            }
        }
        onNodeWithText("Setup failed").assertIsDisplayed()
        onNodeWithText("Last error (provider_refused): No capacity").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        waitUntil { onAllNodesText("Running") }
        assertEquals(listOf("{}"), api.retryBodies)
    }

    @Test
    fun aRefusedRetryShowsTheServersWords() = runRobolectricComposeUiTest {
        val api = FakeApi()
        api.retryResponse = HomeNetworkResponse(code = 409, bodyText = """{"error":"Cloud agents are paused","code":"hosted_runtime_disabled"}""")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersAgentHostedRuntimeSection(MemberId("a1"), failed, RaftAgentsApiClient(api))
            }
        }
        onNodeWithText("Retry").performClick()
        waitUntil { onAllNodesText("Cloud agents are paused") }
        onNodeWithText("Setup failed").assertIsDisplayed()
    }

    @Test
    fun aRetryAfterARefusedModelSaysItUsesTheDefaultAndSendsIt() = runRobolectricComposeUiTest {
        val api = FakeApi()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersAgentHostedRuntimeSection(MemberId("a1"), failed.copy(retryNeedsModel = true), RaftAgentsApiClient(api))
            }
        }
        onNodeWithText("Retry with Provider default").performClick()
        waitUntil { api.retryBodies.isNotEmpty() }
        assertEquals(listOf("""{"model":null}"""), api.retryBodies)
    }

    @Test
    fun aPausedAssistantReadsPausedNotRunning() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersAgentHostedRuntimeSection(
                    MemberId("a1"),
                    AgentHostedRuntimeSnapshot(state = "active"),
                    RaftAgentsApiClient(FakeApi()),
                    paused = true
                )
            }
        }
        onNodeWithText("Paused").assertIsDisplayed()
        assertEquals(false, onAllNodesText("Running"))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesText(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
