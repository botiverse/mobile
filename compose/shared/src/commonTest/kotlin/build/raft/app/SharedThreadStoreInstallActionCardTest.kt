package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardInstallText
import build.raft.app.data.isAuthSessionBlocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `integration:install_marketplace_app` through [SharedThreadDataSource] (web InstallAppCardDialog):
 * the card's own commit never executes it; the confirmation's Install does, with nothing but the
 * card's state and confirmation version; a stale-card 409 keeps the server's code.
 */
class SharedThreadStoreInstallActionCardTest {
    private fun installCard(bound: Boolean = true) =
        ThreadActionCard(
            type = "integration:install_marketplace_app",
            clientName = "Linear",
            agentName = "lumen",
            scopes = listOf("openid"),
            installScopes = if (bound) listOf("openid", "profile") else null,
            installGroups = if (bound) emptyList() else null,
            genericApprovalAllowed = true,
            confirmationVersion = 4,
        )

    @Test
    fun installMarketplaceCardNeverExecutesFromTheCardButton() = runBlocking {
        val actionCardsApi = SharedThreadStoreTest.RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )

        val result = dataSource.commitThreadActionCard(SharedThreadStoreTest.basePayload(), "m-install", installCard())

        assertTrue(result is DataResult.Error)
        assertEquals("action_card_install_confirm_required", result.error.code)
        assertTrue(actionCardsApi.calls.isEmpty())
    }

    @Test
    fun installMarketplaceConfirmationExecutesTheBoundCard() = runBlocking {
        val actionCardsApi = SharedThreadStoreTest.RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )

        val unbound = dataSource.confirmInstallMarketplaceAppActionCard("m-install", installCard(bound = false))
        assertTrue(unbound is DataResult.Error)
        assertEquals("action_card_install_unbound", unbound.error.code)
        assertTrue(actionCardsApi.calls.isEmpty())

        val result = dataSource.confirmInstallMarketplaceAppActionCard("m-install", installCard())

        assertTrue(result is DataResult.Content)
        // web InstallAppCardDialog: no expected_* scopes; the server installs the set it bound.
        assertEquals(1, actionCardsApi.calls.size)
        val body =
            Json.parseToJsonElement(
                actionCardsApi.calls.single().substringAfter("execute body=")
            ) as JsonObject
        assertEquals("prepared", (body["expectedState"] as JsonPrimitive).content)
        assertEquals("4", (body["expectedConfirmationVersion"] as JsonPrimitive).content)
        assertEquals(setOf("expectedState", "expectedConfirmationVersion"), body.keys)
    }

    @Test
    fun installMarketplaceStaleRefusalKeepsTheServerCode() = runBlocking {
        val actionCardsApi =
            SharedThreadStoreTest.RecordingActionCardsApi(
                executeResponse =
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"error":"Linear changed its permissions; prepare a fresh card.","code":"APP_PERMISSIONS_CHANGED"}"""
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )

        val result = dataSource.confirmInstallMarketplaceAppActionCard("m-install", installCard())

        assertTrue(result is DataResult.Error)
        assertEquals("action_execute_contract_mismatch", result.error.code)
        assertEquals("APP_PERMISSIONS_CHANGED", result.error.serverCode)
        assertEquals(1, actionCardsApi.calls.size)
    }

    private fun dataSourceAnswering(response: HomeNetworkResponse) =
        testSharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            actionCardsApiClient = RaftIntegrationsActionsPushApiClient(SharedThreadStoreTest.RecordingActionCardsApi(executeResponse = response))
        )

    @Test
    fun anExpiredSessionRoutesThroughThePageLikeOtherCardPaths() = runTest {
        // The ThreadPage wiring: the real data source, the page's routeAuthRequired.
        val routed = mutableListOf<DataResult.NoPermission>()
        val applied = mutableListOf<String>()
        val store =
            threadInstallAppActionDialogStore(
                scope = backgroundScope,
                dataSource = dataSourceAnswering(HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"Unauthorized"}""")),
                applyCard = { id, _ -> applied += id },
                toastInstalled = {},
                routeAuthRequired = { result -> routed += result; result.blocker.isAuthSessionBlocker() },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", installCard()))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        store.state.first { it == null || !it.submitting }

        assertEquals(1, routed.size, "state=${store.state.value}")
        assertTrue(routed.single().blocker.isAuthSessionBlocker())
        assertNull(store.state.value)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun aNewerTextRevisionRefusalReachesTheDialogAsTextChanged() = runTest {
        val store =
            threadInstallAppActionDialogStore(
                scope = backgroundScope,
                dataSource =
                    dataSourceAnswering(
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            errorBodyText = """{"error":"stale","code":"APP_PERMISSIONS_CHANGED","current_text_revision_id":"rev-2"}"""
                        )
                    ),
                applyCard = { _, _ -> },
                toastInstalled = {},
                routeAuthRequired = { false },
            )

        store.dispatch(
            ThreadInstallAppActionIntent.Open(
                "m-install",
                installCard().copy(installTextRevisionId = "rev-1", installText = ThreadActionCardInstallText(name = "Linear", whenToUse = "x"))
            )
        )
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        store.state.first { it == null || !it.submitting }

        assertEquals(ThreadInstallAppStaleCode.TextChanged, store.state.value?.staleCode, "state=${store.state.value}")
    }
}
