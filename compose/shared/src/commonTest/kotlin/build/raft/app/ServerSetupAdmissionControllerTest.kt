package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SlockServerSetupProjectionApi
import build.raft.app.data.SlockServerSetupProjectionRepository
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ServerSetupAdmissionControllerTest {
    @Test
    fun lateOldServerResponseCannotAdmitNewIdentity() = runTest {
        val oldResponse = CompletableDeferred<HomeNetworkResponse>()
        val api = object : SlockServerSetupProjectionApi {
            override suspend fun projection(serverId: ServerId): HomeNetworkResponse =
                if (serverId.value == "old") oldResponse.await() else admittedResponse()
        }
        val controller = ServerSetupAdmissionController(SlockServerSetupProjectionRepository(api))
        val old = ServerSetupAdmissionIdentity("env", "account", "old", "generation:1")
        val current = old.copy(serverId = "current", generation = "generation:2")

        controller.begin(old)
        val late = async { controller.load(old) }
        controller.begin(current)
        assertIs<ServerSetupAdmissionState.Admitted>(controller.load(current))
        oldResponse.complete(admittedResponse())
        assertNull(late.await())
    }

    private fun admittedResponse() = HomeNetworkResponse(
        code = 200,
        bodyText = """{"surface":"complete","phase":"complete","currentStep":null,"blocksChat":false,"allowedExits":[],"gateReason":"setup_complete"}""",
    )
}
