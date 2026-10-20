package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthenticatedRouteAuthorityTest {
    @Test
    fun mismatchedPersistedOwnerRejectsBeforeNetworkClientOrOperation() = runBlocking {
        var networkClientCalls = 0
        var operationCalls = 0
        val gateway =
            FakeAuthenticatedRouteAuthorityGateway(
                capturedReceipt = null,
                onNetworkClient = { networkClientCalls += 1 },
            )

        val result =
            AuthenticatedRouteOperationRunner(owner(), gateway).run { _, _ ->
                operationCalls += 1
                DataResult.Content("unreachable")
            }

        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, networkClientCalls)
        assertEquals(0, operationCalls)
    }

    @Test
    fun resultIsDroppedWhenAuthorityChangesDuringOperation() = runBlocking {
        val receipt = receipt()
        val gateway =
            FakeAuthenticatedRouteAuthorityGateway(
                capturedReceipt = receipt,
                current = false,
            )

        val result =
            AuthenticatedRouteOperationRunner(owner(), gateway).run { captured, _ ->
                assertEquals(receipt, captured)
                DataResult.Content("stale-content")
            }

        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, assertIs<DataResult.Error>(result).error.code)
        assertEquals(1, gateway.networkClientCalls)
        assertEquals(1, gateway.currentChecks)
    }

    @Test
    fun sessionAccountServerAndEnvironmentMustAllMatchPersistedOwner() {
        val snapshot =
            ThreadReplyCacheIdentitySnapshot(
                session = session(),
                environmentId = "https://api.raft.build/api/",
                principalId = "account-a",
                serverId = "server-a",
                authGeneration = 4L,
                selectionGeneration = 7L,
            )

        assertEquals(receipt(), snapshot.toAuthenticatedRouteAuthorityReceipt(owner()))
        assertNull(snapshot.toAuthenticatedRouteAuthorityReceipt(owner().copy(accountId = "account-b")))
        assertNull(snapshot.toAuthenticatedRouteAuthorityReceipt(owner().copy(serverId = "server-b")))
        assertNull(
            snapshot.toAuthenticatedRouteAuthorityReceipt(
                owner().copy(environmentId = "https://staging.raft.build/api")
            )
        )
    }

    @Test
    fun candidateCacheScopeSeparatesEnvironmentAccountAndServerWithoutTokens() {
        val receipt = receipt()
        val scope = receipt.candidateCacheScopeId()

        assertEquals(scope, receipt.copy(owner = receipt.owner.copy()).candidateCacheScopeId())
        listOf(
            receipt.copy(owner = receipt.owner.copy(environmentId = "https://staging.raft.build/api")),
            receipt.copy(owner = receipt.owner.copy(accountId = "account-b")),
            receipt.copy(owner = receipt.owner.copy(serverId = "server-b")),
            receipt.copy(authGeneration = receipt.authGeneration + 1),
            receipt.copy(selectionGeneration = receipt.selectionGeneration + 1),
        ).forEach { differentReceipt ->
            kotlin.test.assertNotEquals(scope, differentReceipt.candidateCacheScopeId())
        }
        kotlin.test.assertFalse(scope.contains("access-a"))
        kotlin.test.assertFalse(scope.contains("refresh-a"))
    }

    private fun owner(): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner(
            environmentId = "https://api.raft.build/api",
            accountId = "account-a",
            serverId = "server-a",
        )

    private fun receipt(): AuthenticatedRouteAuthorityReceipt =
        AuthenticatedRouteAuthorityReceipt(
            owner = owner(),
            authGeneration = 4L,
            selectionGeneration = 7L,
            canManageSettings = true,
            expectedAuthBinding =
                RaftExpectedAuthBinding(
                    accountId = "account-a",
                    accessToken = "access-a",
                    refreshToken = "refresh-a",
                    serverId = "server-a",
                    environmentId = "https://api.raft.build/api",
                ),
        )

    private fun session(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "account-a", email = "a@raft.build"),
            accessToken = "access-a",
            refreshToken = "refresh-a",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "A", role = "owner")),
        )
}

private class FakeAuthenticatedRouteAuthorityGateway(
    private val capturedReceipt: AuthenticatedRouteAuthorityReceipt?,
    private val current: Boolean = true,
    private val onNetworkClient: () -> Unit = {},
) : AuthenticatedRouteAuthorityGateway {
    var networkClientCalls = 0
    var currentChecks = 0

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? =
        capturedReceipt

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean {
        currentChecks += 1
        return current
    }

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient {
        networkClientCalls += 1
        onNetworkClient()
        return object : RaftNetworkClient {
            override suspend fun request(
                priority: DataRequestPriority,
                path: String,
                method: String,
                query: List<Pair<String, String>>,
                serverIdOverride: ServerId?,
                bodyText: String?,
            ): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
        }
    }
}
