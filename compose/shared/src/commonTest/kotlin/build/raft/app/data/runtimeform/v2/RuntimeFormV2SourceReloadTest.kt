package build.raft.app.data.runtimeform.v2

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The "re-probe" action next to a v2 select (also the `option_source.status` retry) re-requests exactly one option source. */
class RuntimeFormV2SourceReloadTest {
    private class ScriptedSourceApi(private val responses: MutableList<HomeNetworkResponse>) : RaftComputersApi {
        val requests = mutableListOf<List<String>>()

        override suspend fun runtimeFormV2OptionSource(
            serverId: ServerId,
            machineId: String,
            runtimeId: String,
            sourceId: String,
            refresh: Boolean,
            formRevision: String?
        ): HomeNetworkResponse {
            requests += listOf(serverId.value, machineId, runtimeId, sourceId, "refresh=$refresh")
            return responses.removeAt(0)
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

    private fun source(vararg models: String) =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                """{"sourceId":"model","kind":"select","pointer":"/model","options":[""" +
                    models.joinToString(",") { """{"value":"$it","label":"$it"}""" } + "]}"
        )

    @Test
    fun reloadSourceReturnsTheFreshOptionsOfThatOneSource() = runBlocking {
        val api = ScriptedSourceApi(mutableListOf(source("k2", "k2-turbo")))
        val result = RuntimeFormV2Loader(api).reloadSource(ServerId("s1"), "m1", "kimi-sdk", "model")

        val reloaded = assertIs<DataResult.Content<RuntimeFormV2Source>>(result).data
        assertEquals(listOf("k2", "k2-turbo"), reloaded.options?.map { it.value })
        assertEquals(listOf(listOf("s1", "m1", "kimi-sdk", "model", "refresh=true")), api.requests, "a re-probe bypasses the server cache")
    }

    @Test
    fun reloadSourceFailuresComeBackAsErrorsForTheCallerToKeepItsOptions() = runBlocking {
        val loader = RuntimeFormV2Loader(
            ScriptedSourceApi(
                mutableListOf(
                    HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"code":"probe_failed"}"""),
                    HomeNetworkResponse(code = 200, bodyText = """{"kind":"radio"}""")
                )
            )
        )
        assertIs<DataResult.Error>(loader.reloadSource(ServerId("s1"), "m1", "kimi-sdk", "model"))
        assertEquals(
            "runtime_form_v2_option_source_contract_mismatch",
            assertIs<DataResult.Error>(loader.reloadSource(ServerId("s1"), "m1", "kimi-sdk", "model")).error.code
        )
    }

    @Test
    fun reloadSourceNeedsAMachineAndARuntime() = runBlocking {
        val api = ScriptedSourceApi(mutableListOf())
        val loader = RuntimeFormV2Loader(api)
        assertIs<DataResult.Error>(loader.reloadSource(ServerId("s1"), "", "kimi-sdk", "model"))
        assertIs<DataResult.Error>(loader.reloadSource(ServerId("s1"), "m1", " ", "model"))
        assertEquals(emptyList(), api.requests)
    }
}
