package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftServerSetupProjectionApi
import build.raft.app.data.RaftServerSetupProjectionRepository
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class ServerSetupAdmissionControllerTest {
    @Test
    fun lateOldServerResponseCannotAdmitNewIdentity() = runBlocking {
        val oldResponse = CompletableDeferred<HomeNetworkResponse>()
        val api = object : RaftServerSetupProjectionApi {
            override suspend fun projection(serverId: ServerId): HomeNetworkResponse =
                if (serverId.value == "old") oldResponse.await() else admittedResponse()
        }
        val controller = ServerSetupAdmissionController(RaftServerSetupProjectionRepository(api))
        val old = ServerSetupAdmissionIdentity("env", "account", "old", "generation:1")
        val current = old.copy(serverId = "current", generation = "generation:2")

        controller.begin(old)
        val late = async { controller.load(old) }
        controller.begin(current)
        assertIs<ServerSetupAdmissionState.Admitted>(controller.load(current))
        oldResponse.complete(admittedResponse())
        assertNull(late.await())
    }

    @Test
    fun validWakeOnlyRequestsFreshProjectionAndFreshFalseThenAdmits() = runBlocking {
        var requests = 0
        val api = object : RaftServerSetupProjectionApi {
            override suspend fun projection(serverId: ServerId): HomeNetworkResponse {
                requests += 1
                return if (requests == 1) blockedResponse() else admittedResponse()
            }
        }
        val controller = ServerSetupAdmissionController(RaftServerSetupProjectionRepository(api))
        val current = ServerSetupAdmissionIdentity("env", "account", "server", "generation:1")
        controller.begin(current)

        assertIs<ServerSetupAdmissionState.Blocked>(controller.load(current))
        assertTrue(controller.acceptsWake(current))
        assertIs<ServerSetupAdmissionState.Admitted>(controller.load(current))
        assertTrue(requests == 2)
    }

    private fun blockedResponse() = HomeNetworkResponse(
        code = 200,
        bodyText = """{"surface":"computer_runtime","phase":"in_progress","currentStep":"computer_runtime","blocksChat":true,"allowedExits":[],"gateReason":"setup_required"}""",
    )

    private fun admittedResponse() = HomeNetworkResponse(
        code = 200,
        bodyText = """{"surface":"complete","phase":"complete","currentStep":null,"blocksChat":false,"allowedExits":[],"gateReason":"setup_complete"}""",
    )
}
