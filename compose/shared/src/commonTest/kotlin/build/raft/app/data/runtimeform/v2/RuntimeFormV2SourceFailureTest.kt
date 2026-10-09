package build.raft.app.data.runtimeform.v2

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SlockComputersApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One option source that fails to load marks only its own field unavailable (task #141): the rest of the
 * v2 form still renders instead of the whole form falling back to v1. Built-in Pi / Kimi SDK answered an
 * offline computer with a 409 on a source (archer, #raft-mobile-reconcile 276b5c21).
 */
class RuntimeFormV2SourceFailureTest {
    private class FormApi(private val sources: Map<String, HomeNetworkResponse>) : SlockComputersApi {
        override suspend fun runtimeFormV2(serverId: ServerId, machineId: String, runtimeId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = BUILTIN_V2_FORM_JSON)

        override suspend fun runtimeFormV2OptionSource(
            serverId: ServerId,
            machineId: String,
            runtimeId: String,
            sourceId: String,
            refresh: Boolean
        ): HomeNetworkResponse = sources.getValue(sourceId)

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

    private val provider =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                """{"sourceId":"provider","kind":"select","pointer":"/providerId",""" +
                    """"options":[{"value":"deepseek","label":"DeepSeek"}],"defaultValue":"deepseek"}"""
        )

    @Test
    fun aFailingSourceMarksOnlyItsFieldUnavailableAndRetryable() = runBlocking {
        listOf(
            HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"Computer is offline"}"""),
            HomeNetworkResponse(code = 200, bodyText = """{"kind":"radio"}""")
        ).forEach { modelResponse ->
            val loaded =
                assertIs<DataResult.Content<RuntimeFormV2Loaded>>(
                    RuntimeFormV2Loader(FormApi(mapOf("provider" to provider, "model" to modelResponse)))
                        .loadCreate(ServerId("s1"), "m1", "builtin")
                ).data

            assertEquals(listOf("deepseek"), loaded.sources.getValue("provider").options?.map { it.value })
            assertEquals(null, loaded.sources.getValue("provider").status, "the healthy source is untouched")

            val model = loaded.sources.getValue("model")
            assertEquals("unavailable", model.status)
            assertEquals(true, model.retryable)
            assertEquals("/model", model.pointer)
            assertEquals("/providerId", model.dependsOn)

            val modelField = loaded.form.fields.single { it.key == "model" }
            val status = runtimeFormV2SourceStatus(modelField, loaded.sources)
            assertTrue(status?.unavailable == true && status.retryable, "the field shows why and offers a retry")
            val values = initialRuntimeFormV2Values(loaded.form, loaded.sources)
            assertEquals(
                RuntimeFormV2ErrorCode.SourceUnavailable,
                validateRuntimeFormV2(loaded.form, loaded.sources, values)["model"],
                "a required field without options still blocks saving"
            )
        }
    }

    @Test
    fun aPermissionAnswerStillFailsTheForm() = runBlocking {
        val result =
            RuntimeFormV2Loader(
                FormApi(mapOf("provider" to provider, "model" to HomeNetworkResponse(code = 403, bodyText = null)))
            ).loadCreate(ServerId("s1"), "m1", "builtin")
        assertTrue(result !is DataResult.Content, "403 is a real blocker, not a per-field gap: $result")
    }
}
