package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.time.Instant
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

    /**
     * `runtimeUpdates` as the server sends it, botiverse/slock #15429 read at staging
     * 5881c7b53ca7b447e4d4ac391390c68453cba952: the payload of packages/server/src/routes/
     * runtimeUpgrade.api.test.ts "the read carries the Computer's update checks for the runtimes
     * Upgrade is offered for, and only theirs" (shape: packages/shared/src/runtimeUpgrade.ts
     * `runtimeUpdateCheckSchema`, packages/shared/src/runtimeAuth.ts `runtimeUpdatesSchema`).
     */
    @Test
    fun theRuntimeAuthReadCarriesTheComputersUpdateChecks() {
        val base = """"canAct":true,"availability":"ok","status":{"runtimes":{"claude":{"kind":"logged_in"}}}"""
        val checkedAt = "2026-11-18T10:00:00.000Z"
        val checkedAtMillis = Instant.parse(checkedAt).toEpochMilliseconds()
        val view =
            assertNotNull(
                parse(
                    """{$base,"runtimeUpgrade":true,"upgradableRuntimes":["claude","grok"],"runtimeUpdates":{""" +
                        """"claude":{"kind":"ok","installed":"2.0.5","latest":"2.0.5","updateAvailable":false,"checkedAt":"$checkedAt"},""" +
                        """"grok":{"kind":"unavailable","reason":"lookup_failed","checkedAt":"$checkedAt"}}}"""
                ).toRuntimeAuthView()
            )
        assertEquals(
            mapOf(
                RuntimeAuthRuntime.Claude to RuntimeUpdateCheck.Ok(installed = "2.0.5", latest = "2.0.5", updateAvailable = false, checkedAtMillis = checkedAtMillis),
                RuntimeAuthRuntime.Grok to RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.LookupFailed, checkedAtMillis),
            ),
            view.runtimeUpdates,
        )
        // "no update checks until the Computer reports them"; an older server or Computer sends no field.
        assertEquals(
            emptyMap(),
            assertNotNull(parse("""{$base,"runtimeUpgrade":true,"upgradableRuntimes":["claude"],"runtimeUpdates":{}}""").toRuntimeAuthView()).runtimeUpdates,
        )
        assertNull(assertNotNull(parse("""{$base,"runtimeUpgrade":true,"upgradableRuntimes":["claude"]}""").toRuntimeAuthView()).runtimeUpdates)

        // Read only for a runtime Upgrade is offered for, to a viewer who may act, while under a day old (web `runtimeUpdateOf`).
        val minuteLater = checkedAtMillis + 60_000L
        assertEquals(view.runtimeUpdates?.get(RuntimeAuthRuntime.Claude), view.updateCheckOf(RuntimeAuthRuntime.Claude, minuteLater))
        assertNull(view.updateCheckOf(RuntimeAuthRuntime.Codex, minuteLater), "codex is not offered")
        assertNull(view.copy(canAct = false).updateCheckOf(RuntimeAuthRuntime.Claude, minuteLater))
        assertNull(view.copy(upgradableRuntimes = null).updateCheckOf(RuntimeAuthRuntime.Claude, minuteLater), "Upgrade not offered here")
        assertNotNull(view.updateCheckOf(RuntimeAuthRuntime.Claude, checkedAtMillis + RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS))
        assertNull(view.updateCheckOf(RuntimeAuthRuntime.Claude, checkedAtMillis + RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS + 1), "a stale \"Latest\" counts as no answer")
    }

    /**
     * What slock's own schema keeps and drops (packages/shared/src/runtimeAuth.test.ts at 5881c7b53c,
     * "reads each runtime's update check, without a source, a path or a runtime it does not know"),
     * each drop logged rather than silent.
     */
    @Test
    fun anUpdateCheckThisAppCannotReadIsDroppedOnItsOwnAndLogged() {
        val checkedAt = "2026-10-29T10:00:00.000Z"
        val checkedAtMillis = Instant.parse(checkedAt).toEpochMilliseconds()
        val logged = mutableListOf<String>()
        val updates =
            parse(
                """{"antigravity":{"kind":"ok","installed":"1.5.10","latest":"1.5.11","updateAvailable":true,"checkedAt":"$checkedAt","source":"https://example.com/latest"},""" +
                    """"codex":{"kind":"unavailable","reason":"lookup_failed","checkedAt":"$checkedAt","detail":"ENOTFOUND /home/someone"},""" +
                    """"grok":{"kind":"unavailable","reason":"package_manager","checkedAt":"$checkedAt"},""" +
                    """"claude":{"kind":"ok","installed":"/home/someone/.local/bin/agy","latest":"1.5.11","updateAvailable":true,"checkedAt":"$checkedAt"},""" +
                    """"qoder":"not an object","kimi":{"kind":"some_future_kind","checkedAt":"$checkedAt"},"someFutureRuntime":{"kind":"ok"}}"""
            ).toRuntimeUpdates { logged += it }
        assertEquals(
            mapOf(
                RuntimeAuthRuntime.Antigravity to RuntimeUpdateCheck.Ok("1.5.10", "1.5.11", updateAvailable = true, checkedAtMillis = checkedAtMillis),
                RuntimeAuthRuntime.Codex to RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.LookupFailed, checkedAtMillis),
                RuntimeAuthRuntime.Grok to RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.PackageManager, checkedAtMillis),
            ),
            updates,
            "a source or a detail is ignored; a path for a version, a non-object, a kind from later and an unknown runtime drop only their entry",
        )
        // The path and the non-object are rows that did not read; the later kind (no check: Upgrade as before) and runtime are unknown values.
        assertEquals(2, logged.count { it.startsWith("projection_value_rejected source=runtime_updates") }, logged.toString())
        assertEquals(2, logged.count { it.startsWith("projection_value_unknown source=runtime_updates") }, logged.toString())
        assertTrue(logged.any { "field=kind" in it && "action=fallback_no_update_check" in it }, logged.toString())

        // A reason this app does not know reads as a failed lookup, as slock reads a newer Computer's; logged too.
        val reasons = mutableListOf<String>()
        assertEquals(
            mapOf(RuntimeAuthRuntime.Codex to RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.LookupFailed, checkedAtMillis)),
            parse("""{"codex":{"kind":"unavailable","reason":"some_future_reason","checkedAt":"$checkedAt"}}""").toRuntimeUpdates { reasons += it },
        )
        assertTrue(reasons.single().startsWith("projection_value_unknown source=runtime_updates field=reason"), reasons.toString())
        assertTrue("action=fallback_lookup_failed" in reasons.single(), reasons.toString())

        // Every reason slock sends maps to its own value; a check with no usable time is no check.
        RuntimeUpdateCheckUnavailableReason.entries.forEach { reason ->
            val read = parse("""{"codex":{"kind":"unavailable","reason":"${reason.wire}","checkedAt":"$checkedAt"}}""").toRuntimeUpdates {}
            assertEquals(reason, (read?.get(RuntimeAuthRuntime.Codex) as? RuntimeUpdateCheck.Unavailable)?.reason, reason.wire)
        }
        assertEquals(emptyMap(), parse("""{"codex":{"kind":"unavailable","reason":"lookup_failed","checkedAt":"yesterday"}}""").toRuntimeUpdates {})
        assertNull(parse("""["claude"]""").toRuntimeUpdates {})
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

    /**
     * botiverse/slock #17210 (commit 7b7152396): `failed` results may carry OAR 0.138's typed
     * `reason` alongside `exitCode`. All 6 known reasons decode cleanly without diagnostics; unknown
     * valid snake_case reasons log and fall back to null (exit-code copy); invalid or non-string
     * reasons are ignored without dropping the failure outcome.
     */
    @Test
    fun failedInstallFailureReasonParsesKnownOarReasonsAndFallsBackCleanly() {
        val logged = mutableListOf<String>()
        mapOf(
            """{"kind":"failed","exitCode":6,"reason":"download_failed"}""" to
                RuntimeInstallResult.Failed(exitCode = 6, reason = RuntimeInstallFailureReason.DownloadFailed),
            """{"kind":"failed","exitCode":null,"reason":"download_failed"}""" to
                RuntimeInstallResult.Failed(exitCode = null, reason = RuntimeInstallFailureReason.DownloadFailed),
            """{"kind":"failed","exitCode":null,"reason":"non_script_response"}""" to
                RuntimeInstallResult.Failed(exitCode = null, reason = RuntimeInstallFailureReason.NonScriptResponse),
            """{"kind":"failed","exitCode":null,"reason":"timed_out"}""" to
                RuntimeInstallResult.Failed(exitCode = null, reason = RuntimeInstallFailureReason.TimedOut),
            """{"kind":"failed","exitCode":2,"reason":"installer_failed"}""" to
                RuntimeInstallResult.Failed(exitCode = 2, reason = RuntimeInstallFailureReason.InstallerFailed),
            """{"kind":"failed","exitCode":null,"reason":"installer_failed"}""" to
                RuntimeInstallResult.Failed(exitCode = null, reason = RuntimeInstallFailureReason.InstallerFailed),
            """{"kind":"failed","exitCode":0,"reason":"not_found_after_install"}""" to
                RuntimeInstallResult.Failed(exitCode = 0, reason = RuntimeInstallFailureReason.NotFoundAfterInstall),
            """{"kind":"failed","exitCode":0,"reason":"unusable_after_install"}""" to
                RuntimeInstallResult.Failed(exitCode = 0, reason = RuntimeInstallFailureReason.UnusableAfterInstall),
        ).forEach { (wire, expected) ->
            assertEquals(expected, parse(wire).toRuntimeInstallResult { logged += it }, wire)
        }
        assertTrue(logged.isEmpty(), "known OAR failure reasons should not log diagnostics: $logged")

        // Unknown reasons matching RUNTIME_INSTALL_FAILURE_REASON_PATTERN fall back to exitCode with an unknown-type log.
        listOf("checksum_mismatch", "other").forEach { unknown ->
            val unknownLogs = mutableListOf<String>()
            assertEquals(
                RuntimeInstallResult.Failed(exitCode = 1, reason = null),
                parse("""{"kind":"failed","exitCode":1,"reason":"$unknown"}""").toRuntimeInstallResult { unknownLogs += it },
                unknown,
            )
            assertEquals(1, unknownLogs.size, unknownLogs.toString())
            assertTrue(unknownLogs.single().startsWith("projection_value_unknown source=runtime_install_result field=reason"), unknownLogs.toString())
            assertTrue("action=fallback_exit_code" in unknownLogs.single(), unknownLogs.toString())
        }

        // Null, blank, non-string, or out-of-shape reasons are ignored without dropping Failed.
        val overBound = "a".repeat(49)
        listOf(
            """{"kind":"failed","exitCode":1,"reason":null}""",
            """{"kind":"failed","exitCode":1,"reason":""}""",
            """{"kind":"failed","exitCode":1,"reason":"BAD-REASON"}""",
            """{"kind":"failed","exitCode":1,"reason":"$overBound"}""",
            """{"kind":"failed","exitCode":1,"reason":42}""",
            """{"kind":"failed","exitCode":1,"reason":true}""",
            """{"kind":"failed","exitCode":1,"reason":["timed_out"]}""",
            """{"kind":"failed","exitCode":1,"reason":{"code":"timed_out"}}""",
        ).forEach { wire ->
            assertEquals(RuntimeInstallResult.Failed(exitCode = 1, reason = null), parse(wire).toRuntimeInstallResult { logged += it }, wire)
        }
        assertTrue(logged.isEmpty(), "invalid reason shapes should not log unknown-type diagnostics: $logged")
    }

    /**
     * botiverse/slock #17305 (commit dc3153798): `failed` results may carry a redacted `outputTail`
     * (at most 2 KB). Non-blank multiline tails are preserved, outer whitespace is trimmed, and
     * null, blank, non-string, or oversized tails are ignored without dropping the failure outcome.
     */
    @Test
    fun failedInstallOutputTailParsesMultilineAndIgnoresInvalidValues() {
        val multiline = "npm error code E404\nnpm error 404 Not Found - GET https://registry.npmjs.org/@deepseek-ai%2fdsh"
        val logged = mutableListOf<String>()
        assertEquals(
            RuntimeInstallResult.Failed(exitCode = 1, reason = RuntimeInstallFailureReason.InstallerFailed, outputTail = multiline),
            parse(
                """{"kind":"failed","exitCode":1,"reason":"installer_failed","outputTail":"npm error code E404\nnpm error 404 Not Found - GET https://registry.npmjs.org/@deepseek-ai%2fdsh"}"""
            ).toRuntimeInstallResult { logged += it },
        )
        // Trims outer whitespace while preserving internal newlines and indentation.
        assertEquals(
            RuntimeInstallResult.Failed(exitCode = 1, outputTail = "line 1\n  line 2\nline 3"),
            parse("""{"kind":"failed","exitCode":1,"outputTail":"  line 1\n  line 2\nline 3\n"}""").toRuntimeInstallResult { logged += it },
        )
        // Exact max length is accepted.
        val atBound = "a".repeat(RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS)
        assertEquals(
            RuntimeInstallResult.Failed(exitCode = null, outputTail = atBound),
            parse("""{"kind":"failed","exitCode":null,"outputTail":"$atBound"}""").toRuntimeInstallResult { logged += it },
        )
        // Null, blank, non-string, and over-bound outputTail values are ignored cleanly.
        val overBound = "a".repeat(RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS + 1)
        listOf(
            """{"kind":"failed","exitCode":1,"outputTail":null}""",
            """{"kind":"failed","exitCode":1,"outputTail":""}""",
            """{"kind":"failed","exitCode":1,"outputTail":"   \n\t  "}""",
            """{"kind":"failed","exitCode":1,"outputTail":42}""",
            """{"kind":"failed","exitCode":1,"outputTail":true}""",
            """{"kind":"failed","exitCode":1,"outputTail":["line 1","line 2"]}""",
            """{"kind":"failed","exitCode":1,"outputTail":{"lines":["line 1"]}}""",
            """{"kind":"failed","exitCode":1,"outputTail":"$overBound"}""",
        ).forEach { wire ->
            assertEquals(RuntimeInstallResult.Failed(exitCode = 1, outputTail = null), parse(wire).toRuntimeInstallResult { logged += it }, wire)
        }
        assertTrue(logged.isEmpty(), "outputTail decoding must never log diagnostics: $logged")
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
                // Kimi Code installs by its sign-in id, opting into OAR failureReason (#17210) and redacted outputTail (#17305).
                """POST servers/s1/machines/m1/runtimes/kimi/install {"respondAsync":true,"failureReason":true,"outputTail":true}""",
            ),
            api.calls,
        )
    }

    @Test
    fun installRuntimeViaStoreCarriesFailedOutputTailFromAsyncOperation() = runTest {
        val tail = "npm error code E404\nnpm error 404 Not Found"
        val api =
            FakeRuntimeOperationApi(
                answer = { HomeNetworkResponse(code = 202, bodyText = """{"operationId":"$OP"}""") },
                operation = {
                    ok(
                        """{"state":"done","status":200,"body":{"result":{"kind":"failed","exitCode":1,"reason":"installer_failed","outputTail":"npm error code E404\nnpm error 404 Not Found"}}}"""
                    )
                },
            )
        val owner = SharedComputersOwner(environmentId = "https://raft.example", accountId = "user-1", sessionGeneration = "gen-1")
        val store = SharedRuntimeAccountUsageStore(client = RaftComputersApiClient(api), currentIdentity = { owner }, detachedScope = backgroundScope)

        assertEquals(
            RuntimeAuthCall.Done(RuntimeInstallResult.Failed(exitCode = 1, reason = RuntimeInstallFailureReason.InstallerFailed, outputTail = tail)),
            store.installRuntime(owner, serverId = "s1", machineId = "m1", runtime = RuntimeAuthRuntime.Grok),
        )
        assertEquals(
            listOf(
                """POST servers/s1/machines/m1/runtimes/grok/install {"respondAsync":true,"failureReason":true,"outputTail":true}""",
                "GET servers/s1/machine-operations/$OP",
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
