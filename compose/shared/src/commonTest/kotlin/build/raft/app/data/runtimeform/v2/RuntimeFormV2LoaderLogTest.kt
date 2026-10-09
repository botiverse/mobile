package build.raft.app.data.runtimeform.v2

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SlockComputersApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every v2 load and re-probe leaves one diagnostics line (task #147): a device run could not tell whether
 * tapping ↻ sent anything, because only v1's `create_agent_form_load` was logged (HuaTuo cf5e3b7d).
 */
class RuntimeFormV2LoaderLogTest {
    private class FormApi(private val sourceCode: Int) : SlockComputersApi {
        val sourceRefreshes = mutableListOf<Pair<String, Boolean>>()

        override suspend fun runtimeFormV2(serverId: ServerId, machineId: String, runtimeId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = BUILTIN_V2_FORM_JSON)

        override suspend fun runtimeFormV2OptionSource(
            serverId: ServerId,
            machineId: String,
            runtimeId: String,
            sourceId: String,
            refresh: Boolean
        ): HomeNetworkResponse {
            sourceRefreshes += sourceId to refresh
            return HomeNetworkResponse(
                code = sourceCode,
                bodyText =
                    """{"sourceId":"$sourceId","kind":"select","pointer":"/$sourceId",""" +
                        """"options":[{"value":"deepseek","label":"DeepSeek"}]}"""
            )
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            error("unused")
    }

    @Test
    fun aReprobeIsLoggedWithItsSourceAndOutcome() = runBlocking {
        val lines = mutableListOf<String>()
        val api = FormApi(sourceCode = 200)
        val loader = RuntimeFormV2Loader(api, logger = { lines += it })

        loader.loadCreate(ServerId("s1"), "m1", "builtin")
        loader.reloadSource(ServerId("s1"), "m1", "builtin", "provider")

        assertEquals(("provider" to true), api.sourceRefreshes.last(), "the re-probe asks the server to probe again")
        assertEquals(
            listOf(
                "runtime_form_v2_load kind=create serverId=s1 machineId=m1 runtimeId=builtin result=content",
                "runtime_form_v2_load kind=source_refresh serverId=s1 machineId=m1 runtimeId=builtin sourceId=provider result=content"
            ),
            lines
        )
    }

    @Test
    fun aFailedReprobeIsLoggedWithItsErrorCode() = runBlocking {
        val lines = mutableListOf<String>()
        RuntimeFormV2Loader(FormApi(sourceCode = 503), logger = { lines += it })
            .reloadSource(ServerId("s1"), "m1", "builtin", "model")
        assertEquals(
            listOf("runtime_form_v2_load kind=source_refresh serverId=s1 machineId=m1 runtimeId=builtin sourceId=model result=error:runtime_form_v2_http_503"),
            lines
        )
    }
}
