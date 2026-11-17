package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrade and Install from the Computer page against the server's own shapes (botiverse/slock #15117
 * 940212580a `runtimeUpgrade.api.test.ts`, #15214 ffbf283ad2 `runtimeInstall.api.test.ts`,
 * `runtimeAuthRoutes.ts`).
 */
class RuntimeUpgradeInstallContractTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun theRuntimeAuthReadOffersUpgradeAndInstallOnlyWhenTheServerSaysSo() {
        val base = """"canAct":true,"availability":"ok","status":{"runtimes":{"claude":{"kind":"logged_in"}}}"""
        val offered =
            assertNotNull(
                parse(
                    """{$base,"runtimeUpgrade":true,"upgradableRuntimes":["claude","grok"],"runtimeInstall":true,"installableRuntimes":["grok","qwen"]}"""
                ).toRuntimeAuthView()
            )
        assertEquals(listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Grok), offered.upgradableRuntimes)
        assertEquals(listOf(RuntimeAuthRuntime.Grok, RuntimeAuthRuntime.Qwen), offered.installableRuntimes)
        assertTrue(offered.upgradeOffered(RuntimeAuthRuntime.Claude))
        assertFalse(offered.upgradeOffered(RuntimeAuthRuntime.Codex))
        assertTrue(offered.installOffered(RuntimeAuthRuntime.Qwen))
        assertFalse(offered.installOffered(null))

        // Offered for none yet ("nothing until the Computer says").
        val none = assertNotNull(parse("""{$base,"runtimeUpgrade":true,"upgradableRuntimes":[],"runtimeInstall":true,"installableRuntimes":[]}""").toRuntimeAuthView())
        assertEquals(emptyList(), none.upgradableRuntimes)
        assertFalse(none.upgradeOffered(RuntimeAuthRuntime.Claude))

        // The flag off, or a server from before: no field, no button.
        val off = assertNotNull(parse("""{$base}""").toRuntimeAuthView())
        assertNull(off.upgradableRuntimes)
        assertNull(off.installableRuntimes)
        // A list without its flag is not an offer.
        assertNull(assertNotNull(parse("""{$base,"upgradableRuntimes":["claude"]}""").toRuntimeAuthView()).upgradableRuntimes)

        // A viewer who may not act is never offered either (web `runtimeUpgradeOffered`).
        assertFalse(offered.copy(canAct = false).upgradeOffered(RuntimeAuthRuntime.Claude))
        assertFalse(offered.copy(canAct = false).installOffered(RuntimeAuthRuntime.Grok))
    }

    @Test
    fun upgradeResultsReadAsTheServerSendsThem() {
        mapOf(
            """{"kind":"upgraded","from":"2.0.1","to":"2.0.5"}""" to RuntimeUpgradeResult.Upgraded("2.0.1", "2.0.5"),
            """{"kind":"current","version":"2.0.5"}""" to RuntimeUpgradeResult.Current("2.0.5"),
            """{"kind":"current"}""" to RuntimeUpgradeResult.Current(null),
            """{"kind":"unchanged","version":"1.0.0-beta.3"}""" to RuntimeUpgradeResult.Unchanged("1.0.0-beta.3"),
            """{"kind":"failed","exitCode":1}""" to RuntimeUpgradeResult.Failed(1),
            """{"kind":"failed","exitCode":null}""" to RuntimeUpgradeResult.Failed(null),
            """{"kind":"unsupported","reason":"requires_terminal"}""" to RuntimeUpgradeResult.Unsupported(RuntimeUpgradeUnsupportedReason.RequiresTerminal),
            """{"kind":"not_installed"}""" to RuntimeUpgradeResult.NotInstalled,
            """{"kind":"error","code":"in_progress"}""" to RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.InProgress),
            """{"kind":"error","code":"internal"}""" to RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.Internal),
        ).forEach { (wire, expected) -> assertEquals(expected, parse(wire).toRuntimeUpgradeResult {}, wire) }
        // A version that is a path is never shown.
        assertEquals(RuntimeUpgradeResult.Upgraded(null, null), parse("""{"kind":"upgraded","from":"/home/someone/.local/bin/claude"}""").toRuntimeUpgradeResult {})
        // A kind from later, or an unknown reason: no result (the caller says it failed), and the drop is logged.
        val logged = mutableListOf<String>()
        assertNull(parse("""{"kind":"rolled_back"}""").toRuntimeUpgradeResult { logged += it })
        assertEquals(1, logged.size)
        assertNull(parse("""{"kind":"unsupported","reason":"needs_reboot"}""").toRuntimeUpgradeResult {})
    }

    @Test
    fun installPlansAndResultsReadAsTheServerSendsThem() {
        assertEquals(
            RuntimeInstallPlan.Plan(listOf("curl -fsSL https://x.ai/cli/install.sh | bash"), "https://docs.x.ai/docs/grok-build"),
            parse("""{"kind":"plan","steps":["curl -fsSL https://x.ai/cli/install.sh | bash"],"source":"https://docs.x.ai/docs/grok-build"}""").toRuntimeInstallPlan {},
        )
        assertEquals(
            RuntimeInstallPlan.Unsupported(RuntimeInstallUnsupportedReason.RequiresPrivileges, "Needs sudo.", listOf("sudo npm i -g x"), "https://example.com/docs"),
            parse("""{"kind":"unsupported","reason":"requires_privileges","detail":"Needs sudo.","steps":["sudo npm i -g x"],"source":"https://example.com/docs"}""")
                .toRuntimeInstallPlan {},
        )
        assertEquals(RuntimeInstallPlan.Unsupported(RuntimeInstallUnsupportedReason.LineRequired), parse("""{"kind":"unsupported","reason":"line_required"}""").toRuntimeInstallPlan {})
        assertEquals(RuntimeInstallPlan.Error, parse("""{"kind":"error","code":"internal"}""").toRuntimeInstallPlan {})
        // A plan whose page is not https, or with no steps, is not shown as something to confirm.
        assertNull(parse("""{"kind":"plan","steps":["x"],"source":"javascript:alert(1)"}""").toRuntimeInstallPlan {})
        assertNull(parse("""{"kind":"plan","steps":[],"source":"https://example.com"}""").toRuntimeInstallPlan {})

        mapOf(
            """{"kind":"installed","version":"0.1.9"}""" to RuntimeInstallResult.Installed("0.1.9"),
            """{"kind":"already_installed"}""" to RuntimeInstallResult.AlreadyInstalled(null),
            """{"kind":"failed","exitCode":1}""" to RuntimeInstallResult.Failed(1),
            """{"kind":"unsupported","reason":"requires_privileges"}""" to RuntimeInstallResult.Unsupported(RuntimeInstallUnsupportedReason.RequiresPrivileges),
            """{"kind":"error","code":"in_progress"}""" to RuntimeInstallResult.Error(RuntimeOperationErrorCode.InProgress),
        ).forEach { (wire, expected) -> assertEquals(expected, parse(wire).toRuntimeInstallResult {}, wire) }
        assertEquals(RuntimeInstallResult.Installed(null), parse("""{"kind":"installed","version":"/home/someone/.local/bin/grok"}""").toRuntimeInstallResult {})
    }

    @Test
    fun upgradeAndInstallPostOnlyTheAsyncFlagAndWaitOnTheOperation() = runTest {
        val api =
            FakeRuntimeOperationApi(
                answer = { operation ->
                    when (operation) {
                        "upgrade" -> HomeNetworkResponse(code = 202, bodyText = """{"operationId":"$OP"}""")
                        "install-plan" -> ok("""{"plan":{"kind":"plan","steps":["npm install -g @openai/codex"],"source":"https://developers.openai.com/codex"}}""")
                        else -> ok("""{"result":{"kind":"installed","version":"0.1.9"}}""")
                    }
                },
                operation = { ok("""{"state":"done","status":200,"body":{"result":{"kind":"upgraded","from":"2.0.1","to":"2.0.5"}}}""") },
            )
        val client = RaftComputersApiClient(api)

        assertEquals(
            RuntimeAuthCall.Done(RuntimeUpgradeResult.Upgraded("2.0.1", "2.0.5")),
            client.upgradeRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Claude),
        )
        assertEquals(
            RuntimeAuthCall.Done(RuntimeInstallPlan.Plan(listOf("npm install -g @openai/codex"), "https://developers.openai.com/codex")),
            client.runtimeInstallPlan(ServerId("s1"), "m1", RuntimeAuthRuntime.Codex),
        )
        assertEquals(RuntimeAuthCall.Done(RuntimeInstallResult.Installed("0.1.9")), client.installRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Kimi))
        assertEquals(
            listOf(
                """POST servers/s1/machines/m1/runtimes/claude/upgrade {"respondAsync":true}""",
                "GET servers/s1/machine-operations/$OP",
                """POST servers/s1/machines/m1/runtimes/codex/install-plan {"respondAsync":true}""",
                // Kimi Code installs by its sign-in id, as every runtime route names it.
                """POST servers/s1/machines/m1/runtimes/kimi/install {"respondAsync":true}""",
            ),
            api.calls,
        )
    }

    @Test
    fun aRefusalKeepsItsCodeAndTraceIdAndAnUnreadableAnswerIsAFailure() = runTest {
        val refused =
            FakeRuntimeOperationApi(
                answer = {
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"code":"computer_upgrade_required","error":"Update this Computer to upgrade its runtimes from here."}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE)),
                    )
                },
            )
        val call = assertIs<RuntimeAuthCall.Refused>(RaftComputersApiClient(refused).upgradeRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Claude))
        assertEquals(409, call.error.status)
        assertEquals("computer_upgrade_required", call.error.code)
        assertEquals(TRACE, call.error.traceId)

        val unreadable = FakeRuntimeOperationApi(answer = { ok("""{"result":{"kind":"rolled_back","output":"token=abc123"}}""") })
        val malformed = assertIs<RuntimeAuthCall.Refused>(RaftComputersApiClient(unreadable).upgradeRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Claude))
        assertEquals("malformed_response", malformed.error.code)
    }

    private class FakeRuntimeOperationApi(
        private val answer: (operation: String) -> HomeNetworkResponse,
        private val operation: () -> HomeNetworkResponse = { error("no machine-operation read expected") },
    ) : RaftComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun runtimeOperation(serverId: ServerId, machineId: String, runtime: String, operation: String, body: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/runtimes/$runtime/$operation $body"
            return answer(operation)
        }

        override suspend fun machineOperation(serverId: ServerId, operationId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machine-operations/$operationId"
            return operation()
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = unexpected()
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = unexpected()
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            unexpected()

        private fun unexpected(): Nothing = error("unexpected request")
    }

    private companion object {
        const val OP = "5c0b7f2e-1a3d-4e5f-8a9b-0c1d2e3f4a5b"
        const val TRACE = "0123456789abcdef0123456789abcdef"

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}
