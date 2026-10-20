package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftNetworkClient
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RaftFilePreviewRemoteAuthorityTest {
    private val owner =
        AuthenticatedRouteOwner(
            environmentId = "https://api.raft.build/api",
            accountId = "account-a",
            serverId = "server-a"
        )

    @Test
    fun ownerMismatchRejectsBeforeNetworkBlockAndClientResolution() = runBlocking {
        val gateway = RecordingFilePreviewAuthorityGateway(receipt = null)
        val scope = RaftFilePreviewRemoteOperationScope(owner, gateway)
        var blockCalls = 0

        val result =
            scope.run {
                blockCalls += 1
                DataResult.Content("must-not-run")
            }

        val error = assertIs<DataResult.Error>(result)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, error.error.code)
        assertEquals(1, gateway.captureCalls)
        assertEquals(0, gateway.networkClientCalls)
        assertEquals(0, blockCalls)
    }

    @Test
    fun authorityChangeDuringIoDropsLateResult() = runBlocking {
        val receipt = receipt(owner)
        val gateway =
            RecordingFilePreviewAuthorityGateway(
                receipt = receipt,
                currentAfterOperation = false
            )
        val scope = RaftFilePreviewRemoteOperationScope(owner, gateway)
        var blockCalls = 0

        val result =
            scope.run {
                blockCalls += 1
                DataResult.Content("late-payload")
            }

        val error = assertIs<DataResult.Error>(result)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, error.error.code)
        assertEquals(1, gateway.captureCalls)
        assertEquals(1, gateway.networkClientCalls)
        assertEquals(1, gateway.currentChecks)
        assertEquals(1, blockCalls)
    }

    @Test
    fun currentReceiptCommitsExactOperationResult() = runBlocking {
        val receipt = receipt(owner)
        val gateway = RecordingFilePreviewAuthorityGateway(receipt = receipt)
        val scope = RaftFilePreviewRemoteOperationScope(owner, gateway)

        val result = scope.run { DataResult.Content("canonical-payload") }

        assertEquals("canonical-payload", assertIs<DataResult.Content<String>>(result).data)
        assertEquals(1, gateway.captureCalls)
        assertEquals(1, gateway.networkClientCalls)
        assertEquals(1, gateway.currentChecks)
    }
}

private fun receipt(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt =
    AuthenticatedRouteAuthorityReceipt(
        owner = owner,
        authGeneration = 4L,
        selectionGeneration = 7L,
        canManageSettings = false,
        expectedAuthBinding =
            RaftExpectedAuthBinding(
                accountId = owner.accountId,
                accessToken = "access-a",
                refreshToken = "refresh-a",
                serverId = owner.serverId,
                environmentId = owner.environmentId
            )
    )

private class RecordingFilePreviewAuthorityGateway(
    private val receipt: AuthenticatedRouteAuthorityReceipt?,
    private val currentAfterOperation: Boolean = true
) : AuthenticatedRouteAuthorityGateway {
    var captureCalls = 0
    var networkClientCalls = 0
    var currentChecks = 0

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? {
        captureCalls += 1
        return receipt?.takeIf { it.owner == owner }
    }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean {
        currentChecks += 1
        return currentAfterOperation && receipt == this.receipt
    }

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient {
        networkClientCalls += 1
        return object : RaftNetworkClient {
            override suspend fun request(
                priority: DataRequestPriority,
                path: String,
                method: String,
                query: List<Pair<String, String>>,
                serverIdOverride: ServerId?,
                bodyText: String?
            ): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
        }
    }
}
