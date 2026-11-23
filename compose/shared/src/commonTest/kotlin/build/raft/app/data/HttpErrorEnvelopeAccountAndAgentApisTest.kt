package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HttpErrorEnvelopeAccountAndAgentApisTest {
    private val sampleTraceId = "62ac7fa79a3785a6bee414a074f82634"
    private val traceHeaders = mapOf("X-Trace-Id" to listOf(sampleTraceId))

    private class FakeNetwork(
        private val answer: (method: String, path: String) -> HomeNetworkResponse,
    ) : RaftNetworkClient {
        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = answer(method, path)
    }

    @Test
    fun accountAndPreferenceParsersPreserveEnvelopeAndGuardCodeZero() {
        val statusResult =
            assertIs<DataResult.Error>(
                parseSharedSecondFactorStatusResponse(
                    403,
                    """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}""",
                    sampleTraceId,
                )
            )
        assertEquals("second_factor_status_http_403", statusResult.error.code)
        assertEquals("Sign in again to manage two-step verification.", statusResult.error.serverMessage)
        assertEquals("session_family_required", statusResult.error.serverCode)
        assertEquals(sampleTraceId, statusResult.error.traceId)

        val statusTimeout =
            assertIs<DataResult.Error>(
                parseSharedSecondFactorStatusResponse(0, """{"error":"network_timeout"}""", null)
            )
        assertNull(statusTimeout.error.serverMessage)
        assertNull(statusTimeout.error.serverCode)

        val recoveryResult =
            assertIs<DataResult.Error>(
                parseSharedSecondFactorRecoveryResponse(
                    403,
                    """{"message":"Recovery locked by workspace policy.","errorCode":"recovery_locked"}""",
                    sampleTraceId,
                )
            )
        assertEquals("Recovery locked by workspace policy.", recoveryResult.error.serverMessage)
        assertEquals("recovery_locked", recoveryResult.error.serverCode)
        assertEquals(sampleTraceId, recoveryResult.error.traceId)

        val cancelResult =
            assertIs<DataResult.Error>(
                parseSharedSecondFactorRecoveryCancelResponse(
                    409,
                    """{"error":"No active recovery request.","code":"recovery_not_pending"}""",
                    sampleTraceId,
                )
            )
        assertEquals("No active recovery request.", cancelResult.error.serverMessage)
        assertEquals("recovery_not_pending", cancelResult.error.serverCode)
        assertEquals(sampleTraceId, cancelResult.error.traceId)

        val otterResult =
            assertIs<DataResult.Error>(
                parseSharedAssistantOtterVisible(
                    400,
                    """{"error":"assistantOtterVisible must be a boolean","code":"invalid_input"}""",
                    sampleTraceId,
                )
            )
        assertEquals("assistantOtterVisible must be a boolean", otterResult.error.serverMessage)
        assertEquals("invalid_input", otterResult.error.serverCode)
        assertEquals(sampleTraceId, otterResult.error.traceId)

        val railResult =
            assertIs<DataResult.Error>(
                parseSharedRailTabOrderAccount(
                    400,
                    """{"error":"Unknown tab id in railTabOrder.","code":"invalid_tab_order"}""",
                    sampleTraceId,
                )
            )
        assertEquals("Unknown tab id in railTabOrder.", railResult.error.serverMessage)
        assertEquals("invalid_tab_order", railResult.error.serverCode)
        assertEquals(sampleTraceId, railResult.error.traceId)
    }

    @Test
    fun appLoginsArchiveSetupRemovalAndActivityObjectsPreserveRefusalEnvelope() = runTest {
        val refusalResponse =
            HomeNetworkResponse(
                code = 403,
                bodyText = null,
                errorBodyText = """{"error":"Only server owners can perform this action.","code":"owner_required"}""",
                responseHeaders = traceHeaders,
            )
        val network = FakeNetwork { _, _ -> refusalResponse }

        val appList = assertIs<DataResult.Error>(NetworkAppLoginsDataSource(network).list())
        assertEquals("Only server owners can perform this action.", appList.error.serverMessage)
        assertEquals("owner_required", appList.error.serverCode)
        assertEquals(sampleTraceId, appList.error.traceId)

        val appRevoke = assertIs<DataResult.Error>(NetworkAppLoginsDataSource(network).revoke("client-1"))
        assertEquals("Only server owners can perform this action.", appRevoke.error.serverMessage)
        assertEquals("owner_required", appRevoke.error.serverCode)
        assertEquals(sampleTraceId, appRevoke.error.traceId)

        val archiveDs = NetworkServerArchiveDataSource(network)
        val archivedChannels = assertIs<DataResult.Error>(archiveDs.archivedChannels(ServerId("s1")))
        assertEquals("Only server owners can perform this action.", archivedChannels.error.serverMessage)
        assertEquals("owner_required", archivedChannels.error.serverCode)

        val unarchive = assertIs<DataResult.Error>(archiveDs.unarchiveChannel(ServerId("s1"), "c1"))
        assertEquals("channel_unarchive_http_403", unarchive.error.code)
        assertEquals("Only server owners can perform this action.", unarchive.error.serverMessage)
        assertEquals("owner_required", unarchive.error.serverCode)
        assertEquals(sampleTraceId, unarchive.error.traceId)

        val setupRepo = RaftServerSetupProjectionRepository(NetworkRaftServerSetupProjectionApi(network))
        val setup = assertIs<DataResult.Error>(setupRepo.load(ServerId("s1")))
        assertEquals("server_setup_projection_request_failed", setup.error.code)
        assertEquals("Only server owners can perform this action.", setup.error.serverMessage)
        assertEquals("owner_required", setup.error.serverCode)
        assertEquals(sampleTraceId, setup.error.traceId)

        val impact =
            assertIs<DataResult.Error>(
                NetworkMemberRemovalImpactRepository(network).removalImpact(ServerId("s1"), "u1")
            )
        assertEquals("member_removal_impact_http_403", impact.error.code)
        assertEquals("Only server owners can perform this action.", impact.error.serverMessage)
        assertEquals("owner_required", impact.error.serverCode)
        assertEquals(sampleTraceId, impact.error.traceId)

        val activityObj =
            assertIs<DataResult.Error>(
                NetworkActivityObjectsRepository(network).act(
                    serverId = "s1",
                    action = ActivityObjectAction.Done,
                    row = ActivityObjectRow(objectKind = "artifact", objectId = "art-1"),
                )
            )
        assertEquals("http_403", activityObj.error.code)
        assertEquals("Only server owners can perform this action.", activityObj.error.serverMessage)
        assertEquals("owner_required", activityObj.error.serverCode)
        assertEquals(sampleTraceId, activityObj.error.traceId)
    }

    @Test
    fun notificationSettingsAgentOverviewAndLoungePreserveEnvelopeAndKeep404RouteMissing() = runTest {
        val notFoundNetwork =
            FakeNetwork { _, _ ->
                HomeNetworkResponse(
                    code = 404,
                    bodyText = null,
                    errorBodyText = """{"error":"Route not enabled.","code":"feature_disabled"}""",
                    responseHeaders = traceHeaders,
                )
            }
        val previousApiProvider = RaftNotificationLevelStore.apiProvider
        try {
            RaftNotificationLevelStore.apiProvider = { RaftNotificationSettingsApi(notFoundNetwork) }
            val levelResult =
                assertIs<DataResult.Error>(
                    RaftNotificationLevelStore.setLevel(
                        serverId = "s1",
                        scope = RaftNotificationLevelScope.Channel,
                        id = "c1",
                        level = RaftNotificationLevelSetting.Mentions,
                    )
                )
            assertEquals("notification_settings_http_404", levelResult.error.code)
            assertEquals("Route not enabled.", levelResult.error.serverMessage)
            assertEquals("feature_disabled", levelResult.error.serverCode)
            assertEquals(sampleTraceId, levelResult.error.traceId)
        } finally {
            RaftNotificationLevelStore.apiProvider = previousApiProvider
            RaftNotificationLevelStore.clear()
            RaftNotificationCapability.resetForTests()
        }

        val forbiddenNetwork =
            FakeNetwork { _, _ ->
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{"message":"Private agent activity is restricted.","errorCode":"agent_activity_forbidden"}""",
                    responseHeaders = traceHeaders,
                )
            }
        val overviewRepo = RealAgentActivityOverviewRepository(forbiddenNetwork)
        val turns = assertIs<DataResult.NoPermission>(overviewRepo.loadTurns(DataRequestPriority.Visible, MemberId("a1")))
        assertEquals("activity_summaries_403", turns.blocker.code)
        assertEquals("Private agent activity is restricted.", turns.blocker.serverMessage)
        assertEquals("agent_activity_forbidden", turns.blocker.serverCode)
        assertEquals(sampleTraceId, turns.blocker.traceId)

        val opEvents =
            assertIs<DataResult.NoPermission>(
                overviewRepo.loadOperatorEvents(DataRequestPriority.Visible, MemberId("a1"), sinceEpochMillis = null)
            )
        assertEquals("operator_events_403", opEvents.blocker.code)
        assertEquals("Private agent activity is restricted.", opEvents.blocker.serverMessage)
        assertEquals("agent_activity_forbidden", opEvents.blocker.serverCode)
        assertEquals(sampleTraceId, opEvents.blocker.traceId)

        val loungeApi = AgentLoungeApi(forbiddenNetwork)
        val loungeLoad = assertIs<DataResult.Error>(loungeApi.loadLounge(ServerId("s1"), ChannelId("c1")))
        assertEquals("Private agent activity is restricted.", loungeLoad.error.serverMessage)
        assertEquals("agent_activity_forbidden", loungeLoad.error.serverCode)
        assertEquals(sampleTraceId, loungeLoad.error.traceId)

        val loungeUnarchive = assertIs<DataResult.NoPermission>(loungeApi.unarchive(ServerId("s1"), ChannelId("c1")))
        assertEquals("agent_activity_forbidden", loungeUnarchive.blocker.code)
        assertEquals("agent_activity_forbidden", loungeUnarchive.blocker.serverCode)
        assertEquals("Private agent activity is restricted.", loungeUnarchive.blocker.serverMessage)
        assertEquals(sampleTraceId, loungeUnarchive.blocker.traceId)
    }
}
