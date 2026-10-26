package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
}
