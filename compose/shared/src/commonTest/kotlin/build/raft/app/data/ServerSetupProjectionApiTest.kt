package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ServerSetupProjectionApiTest {
    @Test
    fun decodesCanonicalProjectionAndBindsServerAuthority() = runBlocking {
        val transport = RecordingSetupApi(
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"surface":"computer_runtime","phase":"in_progress","currentStep":"computer_runtime","blocksChat":true,"allowedExits":["retry"],"gateReason":"runtime_not_ready","futureField":1}""",
            )
        )
        val result = SlockServerSetupProjectionRepository(transport).load(ServerId("server-a"))

        assertEquals(ServerId("server-a"), transport.serverId)
        val projection = assertIs<DataResult.Content<ServerSetupProjection>>(result).data
        assertTrue(projection.blocksChat)
        assertEquals("computer_runtime", projection.surface)
        assertEquals(setOf("retry"), projection.allowedExits)
    }

    @Test
    fun phaseNullDoesNotFabricateCompletion() = runBlocking {
        val result = SlockServerSetupProjectionRepository(
            RecordingSetupApi(
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"surface":"none","phase":null,"blocksChat":true,"allowedExits":[]}""",
                )
            )
        ).load(ServerId("server-a"))

        val projection = assertIs<DataResult.Content<ServerSetupProjection>>(result).data
        assertEquals(null, projection.phase)
        assertTrue(projection.blocksChat)
    }

    @Test
    fun missingBlocksChatFailsClosedAsContractMismatch() = runBlocking {
        val result = SlockServerSetupProjectionRepository(
            RecordingSetupApi(HomeNetworkResponse(code = 200, bodyText = """{"surface":"none"}"""))
        ).load(ServerId("server-a"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("server_setup_projection_contract_mismatch", error.code)
        assertFalse(error.retryable)
    }
}

private class RecordingSetupApi(
    private val response: HomeNetworkResponse,
) : SlockServerSetupProjectionApi {
    var serverId: ServerId? = null

    override suspend fun projection(serverId: ServerId): HomeNetworkResponse {
        this.serverId = serverId
        return response
    }
}
