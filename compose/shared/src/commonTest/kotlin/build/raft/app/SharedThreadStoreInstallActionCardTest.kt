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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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
    fun installMarketplaceCardNeverExecutesFromTheCardButton() = runTest {
        val actionCardsApi = SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
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
    fun installMarketplaceConfirmationExecutesTheBoundCard() = runTest {
        val actionCardsApi = SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
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
    fun installMarketplaceStaleRefusalKeepsTheServerCode() = runTest {
        val actionCardsApi =
            SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi(
                executeResponse =
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        // botiverse/slock routes/actions.ts handleError for actionCardsService's ActionCardError(409, APP_PERMISSIONS_CHANGED).
                        errorBodyText = """{"error":"The app's permissions changed after this card was prepared; prepare a fresh login request","errorCode":"APP_PERMISSIONS_CHANGED"}"""
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
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

    private fun dataSourceAnswering(storeScope: CoroutineScope, response: HomeNetworkResponse) =
        testSharedThreadDataSource(
            storeScope = storeScope,
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            actionCardsApiClient = RaftIntegrationsActionsPushApiClient(SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi(executeResponse = response))
        )

    @Test
    fun anExpiredSessionRoutesThroughThePageLikeOtherCardPaths() = runTest {
        // The ThreadPage wiring: the real data source, the page's routeAuthRequired.
        val routed = mutableListOf<DataResult.NoPermission>()
        val applied = mutableListOf<String>()
        val store =
            threadInstallAppActionDialogStore(
                scope = backgroundScope,
                dataSource = dataSourceAnswering(backgroundScope, HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"Unauthorized"}""")),
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
    fun theServersStaleCardRefusalsBlockInstallWithTheirOwnCopy() = runTest {
        // botiverse/slock actionCardsService install refusals, as routes/actions.ts handleError sends
        // them (`{ error, errorCode }`). Read as `code`, they all fell to the generic failure with
        // Install still enabled.
        val refusals =
            mapOf(
                "APP_PERMISSIONS_CHANGED" to ThreadInstallAppStaleCode.PermissionsChanged,
                "MARKETPLACE_APP_CHANGED" to ThreadInstallAppStaleCode.AppChanged,
                "SCOPE_BINDING_CHANGED" to ThreadInstallAppStaleCode.ScopesChanged,
                "AGENT_BINDING_CHANGED" to ThreadInstallAppStaleCode.AgentChanged,
            )
        for ((errorCode, expected) in refusals) {
            val store =
                threadInstallAppActionDialogStore(
                    scope = backgroundScope,
                    dataSource =
                        dataSourceAnswering(
                            backgroundScope,
                            HomeNetworkResponse(
                                code = 409,
                                bodyText = null,
                                errorBodyText = """{"error":"Marketplace app identity changed; prepare a fresh login request","errorCode":"$errorCode"}"""
                            )
                        ),
                    applyCard = { _, _ -> },
                    toastInstalled = {},
                    routeAuthRequired = { false },
                )
            store.dispatch(ThreadInstallAppActionIntent.Open("m-install", installCard()))
            store.dispatch(ThreadInstallAppActionIntent.Confirm)
            val state = store.state.first { it == null || !it.submitting }

            assertEquals(expected, state?.staleCode, errorCode)
            assertEquals(true to false, state?.blocked to state?.canInstall, errorCode)
        }
    }

    @Test
    fun aNewerTextRevisionRefusalReachesTheDialogAsTextChanged() = runTest {
        val store =
            threadInstallAppActionDialogStore(
                scope = backgroundScope,
                dataSource =
                    dataSourceAnswering(
                        backgroundScope,
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            // routes/actions.ts sends no current_text_revision_id today (routes/integrations.ts does);
                            // the dialog reads it if it ever comes.
                            errorBodyText = """{"error":"stale","errorCode":"APP_PERMISSIONS_CHANGED","current_text_revision_id":"rev-2"}"""
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
