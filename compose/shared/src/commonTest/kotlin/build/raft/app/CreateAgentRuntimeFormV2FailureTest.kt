package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RuntimeFormV2Availability
import build.raft.app.data.SharedCreateAgentRuntimeCatalog
import build.raft.app.data.SharedCreateAgentRuntimeOption
import build.raft.app.data.currentRuntimeFormV2Availability
import build.raft.app.data.runtimeFormV2Availability
import build.raft.app.data.runtimeform.v2.BUILTIN_V2_FORM_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #8 (retire runtime form v1, artin / HaoHao #wg-runtime-form): runtime form v2 is the create
 * dialog's only runtime form. A failed load stays in v2 (warning + Retry, create disabled), a runtime
 * the server marks without a v2 form is "unavailable" (web parity, slock #11812), and the submit only
 * ever carries the v2 form.
 */
class CreateAgentRuntimeFormV2FailureTest {
    @Test
    fun aFailedV2LoadIsAFailureWithItsCodeNeverV1() {
        assertEquals(
            CreateAgentRuntimeSection.Failed("runtime_form_v2_http_503"),
            createAgentRuntimeSection("codex", RuntimeFormV2Availability.Available, RuntimeFormV2DialogLoad.Failed("runtime_form_v2_http_503"))
        )
        // Before the runtime-options rows arrive nothing says the runtime lacks a form.
        assertEquals(
            CreateAgentRuntimeSection.Failed("x"),
            createAgentRuntimeSection("codex", RuntimeFormV2Availability.Unknown, RuntimeFormV2DialogLoad.Failed("x"))
        )
    }

    @Test
    fun withNoRuntimeChosenNothingIsLoadingOrUnavailable() {
        for (availability in RuntimeFormV2Availability.entries) {
            assertEquals(
                CreateAgentRuntimeSection.NoRuntime,
                createAgentRuntimeSection("", availability, RuntimeFormV2DialogLoad.Loading)
            )
        }
    }

    @Test
    fun aRuntimeWithoutTheV2MarkerIsUnavailableWhateverTheLoadSays() {
        for (load in listOf(RuntimeFormV2DialogLoad.Loading, RuntimeFormV2DialogLoad.Failed("runtime_form_v2_http_404"))) {
            assertEquals(CreateAgentRuntimeSection.Unavailable, createAgentRuntimeSection("codex", RuntimeFormV2Availability.Unavailable, load))
        }
    }

    @Test
    fun aCatalogWithNoMarkedRowAtAllIsUnknownNotUnavailable() {
        // The server marks rows only for a client that sends X-Raft-Runtime-Form-V2: 1; rows that all lack
        // the marker (older server, header dropped) prove nothing, so the form load decides, as when the
        // catalog fails. Create and the runtime switch stay enabled.
        val unmarked =
            SharedCreateAgentRuntimeCatalog(
                machineId = "m-1",
                options = listOf(option("codex", marker = false), option("builtin", marker = false))
            )
        for (runtime in listOf("codex", "builtin", "cursor")) {
            assertEquals(RuntimeFormV2Availability.Unknown, unmarked.runtimeFormV2Availability(runtime), runtime)
            assertEquals(RuntimeFormV2Availability.Unknown, unmarked.currentRuntimeFormV2Availability(runtime), runtime)
        }
        assertEquals(
            RuntimeFormV2Availability.Unknown,
            SharedCreateAgentRuntimeCatalog(machineId = "m-1", options = emptyList()).runtimeFormV2Availability("codex")
        )
        // A failed form load under Unknown is the Retry banner, not "unavailable".
        assertEquals(
            CreateAgentRuntimeSection.Failed("runtime_form_v2_http_503"),
            createAgentRuntimeSection(
                "codex",
                unmarked.runtimeFormV2Availability("codex"),
                RuntimeFormV2DialogLoad.Failed("runtime_form_v2_http_503")
            )
        )
    }

    @Test
    fun createIsDisabledUntilTheV2FormHasLoaded() {
        assertFalse(createAgentRuntimeFormV2SubmitReady(formLoaded = false, hasErrors = false))
        assertFalse(createAgentRuntimeFormV2SubmitReady(formLoaded = true, hasErrors = true))
        assertTrue(createAgentRuntimeFormV2SubmitReady(formLoaded = true, hasErrors = false))
    }

    @Test
    fun theSubmitCarriesOnlyTheV2FormAndNothingBeforeItLoads() {
        assertNull(createAgentDialogSubmit("@bot", "", "m-1", external = false, runtime = "builtin", loaded = null, values = emptyMap()))

        val loaded = builtInForm()
        val submit =
            createAgentDialogSubmit(
                name = " @bot ",
                description = " QA ",
                machineId = "m-1",
                external = false,
                runtime = "builtin",
                loaded = loaded,
                values = initialRuntimeFormV2Values(loaded.form, loaded.sources)
            )
        requireNotNull(submit)
        assertEquals("bot", submit.name)
        assertEquals("QA", submit.description)
        assertEquals("m-1", submit.machineId)
        assertEquals("builtin", submit.runtimeFormV2?.runtimeId)
        assertFalse(submit.external)
    }

    private fun option(runtimeId: String, marker: Boolean) =
        SharedCreateAgentRuntimeOption(
            runtimeId = runtimeId,
            capabilityStatus = "available",
            admissionStatus = "available_for_new",
            admissionReason = null,
            current = false,
            availableForNew = true,
            manageableForCurrentAgent = false,
            canSelectInThisContext = true,
            runtimeFormV2 = marker
        )

    private fun builtInForm(): RuntimeFormV2Loaded =
        runBlocking {
            assertIs<DataResult.Content<RuntimeFormV2Loaded>>(
                RuntimeFormV2Loader(BuiltInFormApi()).loadCreate(ServerId("s-1"), "m-1", "builtin")
            ).data
        }

    private class BuiltInFormApi : RaftComputersApi {
        override suspend fun runtimeFormV2(serverId: ServerId, machineId: String, runtimeId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = BUILTIN_V2_FORM_JSON)

        override suspend fun runtimeFormV2OptionSource(
            serverId: ServerId,
            machineId: String,
            runtimeId: String,
            sourceId: String,
            refresh: Boolean,
            formRevision: String?
        ): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"sourceId":"$sourceId","kind":"select","pointer":"/$sourceId",""" +
                        """"options":[{"value":"deepseek","label":"DeepSeek"}]}"""
            )

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
}
