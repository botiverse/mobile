package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentProviderLimitWindow
import build.raft.app.model.AgentToolStuck
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsApiTest {
    // ── Create wire shape (task #186): absent optional fields are omitted, never explicit null ──

    @Test
    fun createBodyOmitsAbsentOptionalFieldsLikeWeb() {
        val body = buildCreateAgentBody(CreateAgentBody(name = "Noel", machineId = "machine-1"))

        assertEquals("""{"name":"Noel","machineId":"machine-1"}""", body)
        assertFalse("\"external\"" in body, body)
        assertFalse("\"description\"" in body, body)
        assertFalse("null" in body, body)
    }

    @Test
    fun cloudAgentBodyNamesTheProviderAndOmitsAnUnpickedModel() {
        val cloud = CreateAgentBody(name = "Noel", external = true, provider = "antiproton")
        assertEquals("""{"name":"Noel","external":true,"provider":"antiproton"}""", buildCreateAgentBody(cloud))
        assertEquals(
            """{"name":"Noel","external":true,"provider":"antiproton","hostedModel":"gpt-5.6-luna"}""",
            buildCreateAgentBody(cloud.copy(hostedModel = "gpt-5.6-luna"))
        )
    }

    // task #186: the production transport delivers non-2xx bodies as
    // `bodyText = null, errorBodyText = <envelope>` (KtorRaftHttpExecutor),
    // which is the shape the on-device 409 actually arrives in. The recovery
    // code must map it — reading only bodyText left recovery dead on device.
    @Test
    fun createAgentMapsRuntimeFormRejectionFromRealTransportErrorBody() = runPortableCoroutineTest {
        val client =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 409,
                    createAgentErrorBody =
                        """{"error":"Runtime form definition is stale or invalid","issues":[{"code":"form_definition_ref_required","pointer":"/formDefinitionRef"}]}"""
                )
            )

        val error = assertIs<DataResult.Error>(client.createAgent("{}"))

        assertEquals("form_definition_ref_required", error.error.code)
    }

    @Test
    fun createAgentMapsNameConflictTextToHumaneCode() = runPortableCoroutineTest {
        val taken =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 409,
                    createAgentErrorBody = """{"error":"Agent name \"Nova\" is already taken"}"""
                )
            ).createAgent("{}")
        val exists =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 409,
                    createAgentErrorBody = """{"error":"Cindy agent already exists in this server"}"""
                )
            ).createAgent("{}")
        val confusable =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 409,
                    createAgentErrorBody = """{"code":"handle_confusable","error":"Agent name is too similar to an existing name. Choose another name."}"""
                )
            ).createAgent("{}")

        assertEquals(AGENT_CREATE_NAME_TAKEN, assertIs<DataResult.Error>(taken).error.code)
        assertEquals(AGENT_CREATE_NAME_TAKEN, assertIs<DataResult.Error>(exists).error.code)
        assertEquals(AGENT_CREATE_NAME_CONFUSABLE, assertIs<DataResult.Error>(confusable).error.code)
    }

    @Test
    fun agentsErrorsCarryRawServerTextForHonestFallback() = runPortableCoroutineTest {
        val unmappedConflict =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 409,
                    createAgentErrorBody = """{"error":"No machine assigned. Please assign a machine to this agent first.","code":"machine_unassigned"}"""
                )
            ).createAgent("{}")
        val serverError =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    createAgentCode = 503,
                    createAgentErrorBody = """{"error":"Scheduled maintenance in progress"}"""
                )
            ).createAgent("{}")

        val conflictError = assertIs<DataResult.Error>(unmappedConflict).error
        assertEquals("http_409", conflictError.code)
        assertEquals(
            "No machine assigned. Please assign a machine to this agent first.",
            conflictError.serverMessage
        )
        val unavailable = assertIs<DataResult.Error>(serverError).error
        assertEquals("server_unavailable", unavailable.code)
        assertEquals("Scheduled maintenance in progress", unavailable.serverMessage)
    }

    @Test
    fun typedAgentsApiAcceptsCanonicalUpstreamShapes() = runPortableCoroutineTest {
        val transport = RecordingAgentsApi()
        val client = RaftAgentsApiClient(transport)
        val agentId = MemberId("agent-1")

        assertEquals(1, assertIs<DataResult.Content<AgentsPage>>(client.agents()).data.agentCount)
        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(agentId)).data
        assertEquals("agent-1", detail.id)
        assertEquals("Noel Bot", detail.displayName)
        assertEquals("admin", detail.serverRole)
        assertEquals("machine-1", detail.machineId)
        assertEquals("codex", detail.runtime)
        assertEquals("preset", detail.runtimeModelKind)
        assertEquals("gpt-5.6-sol", detail.model)
        assertEquals("medium", detail.reasoning)
        assertEquals("local", detail.executionMode)
        assertEquals("0.57.1", detail.daemonVersion)
        assertEquals("pixel:robot", detail.avatarUrl)
        assertEquals("user-1", detail.creatorId)
        assertEquals("human", detail.creatorType)
        assertEquals("Artin", detail.creator?.displayName)
        assertEquals("artin", detail.creator?.name)
        assertEquals("pixel:user", detail.creator?.avatarUrl)
        assertEquals(listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY"), detail.envVarNames)
        assertEquals("Helper", detail.createdAgents.single().displayName)
        val created = assertIs<DataResult.Content<AgentSummary>>(client.createAgent("""{"name":"Noel"}""")).data
        assertEquals("agent-2", created.id)
        assertEquals("pixel:tree", created.avatarUrl)
        assertEquals("Agent One", assertIs<DataResult.Content<AgentSummary>>(client.updateAgent(agentId, """{"displayName":"Agent One"}""")).data.name)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.startAgent(agentId)).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.stopAgent(agentId)).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.resetAgent(agentId, """{"mode":"session"}""")).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.deleteAgent(agentId, AgentArchiveWorkspaceChoice.Delete.toRequestBody())).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.assignMachine(agentId, """{"machineId":"machine-1"}""")).data.ok)
        assertEquals(1, assertIs<DataResult.Content<AgentDmPage>>(client.agentDirectMessages(agentId)).data.itemCount)
        assertEquals(1, assertIs<DataResult.Content<AgentActivityPage>>(client.agentActivityLog(agentId)).data.entryCount)
        assertEquals(1, assertIs<DataResult.Content<AgentWorkspaceFilePage>>(client.agentWorkspaceFiles(agentId)).data.fileCount)
        assertEquals("/repo/README.md", assertIs<DataResult.Content<AgentWorkspaceFileRead>>(client.readAgentWorkspaceFile(agentId, "/repo/README.md")).data.path)
        assertEquals(1, assertIs<DataResult.Content<AgentSkillsSummary>>(client.agentSkills(agentId)).data.globalCount)
        assertEquals(2, assertIs<DataResult.Content<AgentScopeSet>>(client.agentScopes(agentId)).data.granted.size)
        val scopesUpdateBody = client.updateAgentScopes(agentId, listOf("server:read", "channel:read"))
        assertEquals(2, assertIs<DataResult.Content<AgentScopeSet>>(scopesUpdateBody).data.granted.size)
        val loginToken =
            assertIs<DataResult.Content<AgentLoginTokenSummary>>(
                client.issueAgentLoginToken(agentId)
            ).data
        assertEquals("credential-1", loginToken.credentialId)
        assertEquals("sk_agent_secret", loginToken.apiKey)

        val bootstrapToken =
            assertIs<DataResult.Content<AgentBootstrapTokenSummary>>(
                client.issueBootstrapToken(agentId, """{"ttlMs":60000}""")
            ).data
        assertEquals(true, bootstrapToken.bootstrapTokenPresent)
        assertEquals("sk_agent_secret", bootstrapToken.bootstrapToken)

        assertEquals(
            listOf(
                "GET agents",
                "GET agents/agent-1",
                """POST agents body={"name":"Noel"}""",
                """PATCH agents/agent-1 body={"displayName":"Agent One"}""",
                "POST agents/agent-1/start",
                "POST agents/agent-1/stop",
                """POST agents/agent-1/reset body={"mode":"session"}""",
                """DELETE agents/agent-1 body={"workspace":"delete"}""",
                """POST agents/agent-1/assign-machine body={"machineId":"machine-1"}""",
                "GET agents/agent-1/agent-dms",
                "GET agents/agent-1/activity-log limit=50",
                "GET agents/agent-1/workspace-files dirPath=null",
                "GET agents/agent-1/workspace-files/read path=/repo/README.md",
                "GET agents/agent-1/skills",
                "GET agents/agent-1/scopes",
                """PUT agents/agent-1/scopes body={"scopes":["server:read","channel:read"]}""",
                """POST agents/agent-1/credentials body={}""",
                """POST agents/agent-1/bootstrap-tokens body={"ttlMs":60000}"""
            ),
            transport.calls
        )
    }

    @Test
    fun publicAgentDetailLoadsOnlyTheJointAuthorizedAgentProfile() = runPortableCoroutineTest {
        val transport = RecordingAgentsApi()
        val detail =
            assertIs<DataResult.Content<AgentDetailSnapshot>>(
                RaftAgentsApiClient(transport).publicAgentDetail(MemberId("agent-1"))
            ).data

        assertEquals("agent-1", detail.agentId.value)
        assertEquals("Noel Bot", detail.summary?.displayName)
        assertEquals(emptyList(), detail.directMessages)
        assertEquals(emptyList(), detail.channels)
        assertEquals(emptyList(), detail.reminders)
        assertEquals(emptyList(), detail.activity)
        assertEquals(listOf("GET agents/agent-1"), transport.calls)
    }

    @Test
    fun repeatedAgentReadsNeverRetainRuntimeProviderSecrets() = runPortableCoroutineTest {
        val sentinel = "sentinel-provider-secret"
        val client = RaftAgentsApiClient(
            RecordingAgentsApi(
                agentBody =
                    """{"id":"agent-1","name":"Pi","runtimeConfig":{"version":1,"runtime":"builtin","provider":{"kind":"preset","providerId":"deepseek","apiKey":"$sentinel"},"model":{"kind":"preset","id":"deepseek/deepseek-v4-pro"},"mode":{"kind":"default"},"hostUserState":"forbidden"}}"""
            )
        )

        repeat(2) {
            val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(MemberId("agent-1"))).data
            assertEquals("builtin", detail.runtime)
            assertEquals("deepseek/deepseek-v4-pro", detail.model)
            assertEquals(RuntimeProviderConfig.Preset("deepseek", ""), detail.runtimeProvider)
            assertEquals(false, sentinel in detail.toString())
        }
    }

    @Test
    fun agentDetailReadsTheSavedReasoningLevelFirst() = runPortableCoroutineTest {
        // botiverse/slock staging c0f6195c6 `agents.api.test.ts`: Pi's "off" is kept in runtimeConfig
        // only, the `reasoningEffort` column stays empty. The profile reads the saved config's level
        // first (web `hydrateRuntimeConfigForm`), as the Members list does.
        val piOff =
            """{"id":"agent-1","name":"pi-off","status":"active","runtime":"pi","model":"anthropic/sonnet-live","reasoningEffort":null,""" +
                """"runtimeConfig":{"version":1,"runtime":"pi","provider":{"kind":"default"},"model":{"kind":"preset","id":"anthropic/sonnet-live"},""" +
                """"mode":{"kind":"default"},"reasoningEffort":"off","envVars":null}}"""
        suspend fun reasoning(body: String): String? =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-1")))
                .data.reasoning

        assertEquals("off", reasoning(piOff))
        // A config of another version is ignored: the agent's own field decides.
        assertEquals(
            "high",
            reasoning(piOff.replace(""""reasoningEffort":null""", """"reasoningEffort":"high"""").replace(""""version":1""", """"version":2""")),
        )
    }

    @Test
    fun agentDetailCarriesTheWakePauseOnlyWithItsDetailKind() = runPortableCoroutineTest {
        // GET /agents/:id at botiverse/slock 24873fd5be37 (routes/agents.ts): `activityDetailKind`
        // and the shared WakeCrashLoopActivityDiagnostic, `lastExitReason` from #10860.
        val paused =
            """{"id":"agent-1","name":"Agent One","status":"active","activity":"offline",""" +
                """"activityDetail":"Automatic wake paused: stopped 3 times in a row at this Computer's per-agent memory limit",""" +
                """"activityDetailKind":"wake_crash_loop_blocked","wakeCrashLoop":{"episode":2,"earlyExitCount":3,"threshold":3,""" +
                """"windowMs":120000,"blocked":true,"blockedAtMs":1782367200000,"firstExitAtMs":1782360000000,""" +
                """"lastExitAtMs":1782367100000,"lastExitKind":"agent_process_exited","lastSignal":"SIGKILL",""" +
                """"lastLaunchId":"launch-1","lastExitReason":"memory_limit"}}"""
        val detail =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(RecordingAgentsApi(agentBody = paused)).agent(MemberId("agent-1"))
            ).data.toAgentDetailSummary()
        assertEquals("wake_crash_loop_blocked", detail.activityDetailKind)
        assertEquals(AgentWakeCrashLoop(earlyExitCount = 3, lastExitReason = "memory_limit"), detail.wakeCrashLoop)
        assertTrue(detail.wakeCrashLoop!!.pausedAtMemoryLimit)

        // An older server sends no reason; a carrier beside another kind is not a pause.
        val older = paused.replace(""","lastExitReason":"memory_limit"""", "")
        val olderDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = older)).agent(MemberId("agent-1"))).data
        assertEquals(AgentWakeCrashLoop(earlyExitCount = 3), olderDetail.wakeCrashLoop)
        assertEquals(false, olderDetail.wakeCrashLoop!!.pausedAtMemoryLimit)
        val otherKind = paused.replace(""""activityDetailKind":"wake_crash_loop_blocked"""", """"activityDetailKind":"stopped"""")
        val otherDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = otherKind)).agent(MemberId("agent-1"))).data
        assertEquals("stopped", otherDetail.activityDetailKind)
        assertEquals(null, otherDetail.wakeCrashLoop)
    }

    @Test
    fun agentDetailCarriesTheRuntimeErrorCodes() = runPortableCoroutineTest {
        // botiverse/slock #13949 `withRuntimeErrorProjection`, as a member reads it: the kept error
        // and the error activity come with their code and the code's sentence, never the text.
        val sentence =
            "The agent's model provider usage limit has been reached. Wait for the limit to reset, or ask the agent's owner to switch it to another model or account."
        val coded =
            """{"id":"agent-1","name":"Agent One","status":"active","activity":"error",""" +
                """"activityDetail":"$sentence","activityDetailKind":"runtime_error","activityErrorCode":"usage_limit",""" +
                """"lastRuntimeError":{"message":"$sentence","code":"usage_limit","at":"2026-11-10T00:00:00Z","actionRequired":false}}"""
        val detail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = coded)).agent(MemberId("agent-1")))
                .data.toAgentDetailSummary()
        assertEquals(AgentRuntimeErrorCode.UsageLimit, detail.activityErrorCode)
        assertEquals(AgentRuntimeErrorCode.UsageLimit, detail.lastRuntimeErrorCode)

        // The activity's code only while the activity is an error (web activityTypedCarriers).
        val offline = coded.replace(""""activity":"error"""", """"activity":"offline"""")
        val offlineDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = offline)).agent(MemberId("agent-1"))).data
        assertEquals(null, offlineDetail.activityErrorCode)
        assertEquals(AgentRuntimeErrorCode.UsageLimit, offlineDetail.lastRuntimeErrorCode)

        // An older server (no code) and a newer server's unknown code: none.
        val older = coded.replace(""","activityErrorCode":"usage_limit"""", "").replace(""","code":"usage_limit"""", "")
        val olderDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = older)).agent(MemberId("agent-1"))).data
        assertEquals(null, olderDetail.activityErrorCode)
        assertEquals(null, olderDetail.lastRuntimeErrorCode)
        val unknown = coded.replace("usage_limit", "quota_galaxy")
        val unknownDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = unknown)).agent(MemberId("agent-1"))).data
        assertEquals(null, unknownDetail.activityErrorCode)
        assertEquals(null, unknownDetail.lastRuntimeErrorCode)
    }

    @Test
    fun agentDetailCarriesTheHeldReasonOnTheIdleSnapshotOnly() = runPortableCoroutineTest {
        // GET /agents/:id (botiverse/slock #13276 routes/agents.ts; rows from web
        // credentialHoldProjection.test.ts, staging 853c6998b): `credentialHold` beside an idle status.
        suspend fun detailOf(extra: String): AgentDetailSummary =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(
                    RecordingAgentsApi(agentBody = """{"id":"agent-1","name":"held","runtime":"codex","model":"gpt-x","status":"active",$extra}""")
                ).agent(MemberId("agent-1"))
            ).data.toAgentDetailSummary()
        assertEquals(
            AgentCredentialHoldReason.UserReauth,
            detailOf(""""activity":"online","activityDetail":"held notice","activityDetailKind":"idle","credentialHold":{"reason":"user_reauth"}""").credentialHold,
        )
        assertEquals(
            null,
            detailOf(""""activity":"working","activityDetail":"Running","activityDetailKind":"running_command","credentialHold":{"reason":"user_reauth"}""").credentialHold,
            "only the idle snapshot carries it",
        )
        assertEquals(null, detailOf(""""activity":"online","activityDetail":"held notice","activityDetailKind":"idle"""").credentialHold, "an older daemon")
        assertEquals(
            null,
            detailOf(""""activity":"online","activityDetail":"held notice","activityDetailKind":"idle","credentialHold":{"reason":"quota"}""").credentialHold,
            "an unknown reason is none",
        )
    }

    @Test
    fun agentDetailAndRosterCarryDeliveryHoldAcrossAnyActivity() = runPortableCoroutineTest {
        // GET /agents/:id and GET /agents (botiverse/slock #16333): `deliveryHold` rides any activity.
        suspend fun detailOf(extra: String): AgentDetailSummary =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(
                    RecordingAgentsApi(agentBody = """{"id":"agent-1","name":"held","runtime":"claude","model":"claude-sonnet-4-6","status":"active",$extra}""")
                ).agent(MemberId("agent-1"))
            ).data.toAgentDetailSummary()

        val workingQuota =
            detailOf(
                """"activity":"working","activityDetail":"Running","activityDetailKind":"running_command","deliveryHold":{"reason":"provider_limit","detail":"quota_exhausted","untilMs":1794485000000,"since":1794484700000}"""
            )
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 1794485000000L,
                since = 1794484700000L,
            ),
            workingQuota.deliveryHold,
        )

        val credHold =
            detailOf(
                """"activity":"online","activityDetail":"held notice","activityDetailKind":"idle","credentialHold":{"reason":"user_reauth"},"deliveryHold":{"reason":"credential_invalid","detail":"user_reauth","since":1794484700000}"""
            )
        assertEquals(AgentCredentialHoldReason.UserReauth, credHold.credentialHold)
        assertEquals(
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.UserReauth,
                since = 1794484700000L,
            ),
            credHold.deliveryHold,
        )

        assertEquals(
            null,
            detailOf(""""activity":"working","activityDetail":"Running","deliveryHold":null""").deliveryHold,
        )
        assertEquals(
            null,
            detailOf(""""activity":"online","deliveryHold":"malformed"""").deliveryHold,
            "unexpected wire shape does not break agent decoding",
        )

        // Roster parser (`GET /agents` -> MemberProfile.agentDeliveryHold)
        val roster =
            parseSharedAgentProfile(
                """{"id":"agent-1","name":"held","runtime":"claude","model":"claude-sonnet-4-6","status":"active","activity":"working","deliveryHold":{"reason":"provider_limit","detail":"quota_exhausted","untilMs":1794485000000,"since":1794484700000}}"""
            )
        assertEquals(workingQuota.deliveryHold, roster?.agentDeliveryHold)

        // botiverse/slock #16629: scoped provider_limit model, window and resetsAtMs survive detail + roster parsing.
        val scopedWire =
            """{"reason":"provider_limit","detail":"quota_exhausted","untilMs":1794485000000,"since":1794484700000,"model":"Fable","window":"seven_day","resetsAtMs":1794484999000}"""
        val expectedScoped =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 1794485000000L,
                since = 1794484700000L,
                model = "Fable",
                window = AgentProviderLimitWindow.SevenDay,
                resetsAtMs = 1794484999000L,
            )
        assertEquals(
            expectedScoped,
            detailOf(""""activity":"working","deliveryHold":$scopedWire""").deliveryHold,
        )
        assertEquals(
            expectedScoped,
            parseSharedAgentProfile("""{"id":"agent-1","name":"held","runtime":"claude","model":"fable","status":"active","activity":"working","deliveryHold":$scopedWire}""")?.agentDeliveryHold,
        )
    }

    @Test
    fun theKeptErrorCarriesItsRequiredAction() = runPortableCoroutineTest {
        // botiverse/slock #13276: the stored lastRuntimeError keeps the typed carrier's `requiredAction`
        // (web agentRuntimeErrorBanner.behavior.test.tsx "4b" rows).
        suspend fun detailOf(lastRuntimeError: String): AgentDetailSummary =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(
                    RecordingAgentsApi(
                        agentBody =
                            """{"id":"agent-1","name":"own","runtime":"codex","model":"gpt-x","status":"active","activity":"error",""" +
                                """"activityDetail":"boom","activityDetailKind":"runtime_error","lastRuntimeError":$lastRuntimeError}"""
                    )
                ).agent(MemberId("agent-1"))
            ).data.toAgentDetailSummary()
        val own = detailOf("""{"message":"boom","errorClass":"AuthError","errorReason":"auth_failed","requiredAction":"user_reauth"}""")
        assertEquals(AgentCredentialHoldReason.UserReauth, own.lastRuntimeErrorRequiredAction)
        assertEquals("AuthError", own.lastRuntimeErrorClass)
        assertEquals(
            AgentCredentialHoldReason.UserModelAccess,
            detailOf("""{"message":"boom","errorClass":"ModelConfigError","errorReason":"model_config_error","requiredAction":"user_model_access"}""")
                .lastRuntimeErrorRequiredAction,
        )
        // Legacy errors (no field), and values outside RUNTIME_ERROR_REQUIRED_ACTIONS, are none.
        assertEquals(null, detailOf("""{"message":"boom","errorClass":"AuthError","errorReason":"auth_failed"}""").lastRuntimeErrorRequiredAction)
        assertEquals(null, detailOf("""{"message":"boom","requiredAction":"billing"}""").lastRuntimeErrorRequiredAction)
    }

    @Test
    fun agentDetailCarriesToolStuckWithAnyActivity() = runPortableCoroutineTest {
        // GET /agents/:id (botiverse/slock routes/agents.ts, #10409): `toolStuck` rides any activity
        // while the agent is stuck on one tool call; absent when it is not.
        val stuck =
            """{"id":"agent-1","name":"Agent One","status":"active","activity":"working",""" +
                """"activityDetail":"Running command…","activityDetailKind":"running_command",""" +
                """"toolStuck":{"toolName":"bash","toolDetailKind":"running_command","startedAtMs":1000,""" +
                """"quietSinceMs":2000,"thresholdMs":600000,"queuedMessages":2}}"""
        val detail =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(RecordingAgentsApi(agentBody = stuck)).agent(MemberId("agent-1"))
            ).data.toAgentDetailSummary()
        assertEquals(
            AgentToolStuck(
                toolName = "bash",
                toolDetailKind = "running_command",
                startedAtMs = 1000L,
                quietSinceMs = 2000L,
                thresholdMs = 600000L,
                queuedMessages = 2
            ),
            detail.toolStuck
        )
        val plain = stuck.substringBefore(""","toolStuck"""") + "}"
        val plainDetail =
            assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = plain)).agent(MemberId("agent-1"))).data
        assertEquals(null, plainDetail.toolStuck)
    }

    @Test
    fun agentModelLimitsWarnOnlyWhenTheServerReportsDefaults() = runPortableCoroutineTest {
        // botiverse/slock #13492 (routes/agents.ts, routes/agentModelLimits.api.test.ts @ f2aef25):
        // the detail, the list and the PATCH answer carry `modelLimits: { defaultsReported }` for a
        // Built-in custom gateway agent; others, and older servers, send nothing (PATCH sends null).
        val base = """{"id":"agent-1","name":"Pi","runtime":"builtin","status":"active""""
        suspend fun detail(tail: String): AgentSummary =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(RecordingAgentsApi(agentBody = "$base$tail}")).agent(MemberId("agent-1"))
            ).data

        assertTrue(detail(""","modelLimits":{"defaultsReported":true}""").toAgentDetailSummary().modelLimitsDefaultsReported)
        assertFalse(detail(""","modelLimits":{"defaultsReported":false}""").modelLimitsDefaultsReported)
        assertFalse(detail(""","modelLimits":null""").modelLimitsDefaultsReported)
        assertFalse(detail("").modelLimitsDefaultsReported)

        val list =
            assertIs<DataResult.Content<AgentsPage>>(
                RaftAgentsApiClient(
                    RecordingAgentsApi(
                        agentsBody = "[$base,\"modelLimits\":{\"defaultsReported\":true}},{\"id\":\"agent-2\",\"name\":\"Old\"}]"
                    )
                ).agents()
            ).data.agents
        assertEquals(listOf(true, false), list.map { it.modelLimitsDefaultsReported })
    }

    @Test
    fun agentDetailDecodesManagedByAppAttributionAndProfileProjection() = runPortableCoroutineTest {
        val body =
            """{"id":"agent-bot","name":"stamp","displayName":"Stamp","runtime":"external","status":"active","managedByAppInstallId":"install-1","managedByApp":{"clientId":"stamp-row-id","name":"Stamp","logoUrl":"https://cdn.example.test/stamp.png","description":"Code review","botStatusText":"Watching PRs"},"profileProjection":"channel_summary"}"""
        val summary =
            assertIs<DataResult.Content<AgentSummary>>(
                RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-bot"))
            ).data.toAgentDetailSummary()

        assertEquals("install-1", summary.managedByAppInstallId)
        assertEquals("stamp-row-id", summary.managedByAppClientId)
        assertEquals("Stamp", summary.managedByAppName)
        assertEquals("https://cdn.example.test/stamp.png", summary.managedByAppLogoUrl)
        assertEquals("Watching PRs", summary.managedByAppBotStatusText)
        assertEquals("channel_summary", summary.profileProjection)
    }

    @Test
    fun typedAgentsApiPreservesCustomRuntimeModelKind() = runPortableCoroutineTest {
        val client =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody =
                        """{"id":"agent-1","name":"Agent One","model":"legacy-flat","runtime":"claude","runtimeConfig":{"version":1,"runtime":"claude","provider":{"kind":"custom","apiUrl":"https://example.invalid","apiKey":"secret"},"model":{"kind":"custom","name":"vendor/private-model"},"mode":{"kind":"default"}}}"""
                )
            )

        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(MemberId("agent-1"))).data

        assertEquals("claude", detail.runtime)
        assertEquals("custom", detail.runtimeModelKind)
        assertEquals("vendor/private-model", detail.model)
    }

    @Test
    fun typedAgentsApiPreservesPiProviderConfiguration() = runPortableCoroutineTest {
        val client =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody =
                        """{"id":"agent-1","name":"Pi","runtimeConfig":{"version":1,"runtime":"pi","provider":{"kind":"pi-builtin","providerId":"deepseek","apiKey":"pi-key"},"model":{"kind":"preset","id":"deepseek/deepseek-v4-pro"},"mode":{"kind":"default"}}}"""
                )
            )

        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(MemberId("agent-1"))).data

        assertEquals("pi", detail.runtime)
        assertEquals("deepseek/deepseek-v4-pro", detail.model)
        assertEquals(RuntimeProviderConfig.PiBuiltin("deepseek", "pi-key"), detail.runtimeProvider)
    }

    @Test
    fun typedAgentsApiPreservesPiSdkProviderConfigurationAndRejectsOtherRuntimes() = runPortableCoroutineTest {
        val piSdkClient =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody =
                        """{"id":"agent-2","name":"PiSdk","runtimeConfig":{"version":1,"runtime":"pi-sdk","provider":{"kind":"pi-builtin","providerId":"deepseek","apiKey":"pi-key"},"model":{"kind":"preset","id":"deepseek/deepseek-v4-pro"},"mode":{"kind":"default"}}}"""
                )
            )
        val piSdkDetail = assertIs<DataResult.Content<AgentSummary>>(piSdkClient.agent(MemberId("agent-2"))).data

        assertEquals("pi-sdk", piSdkDetail.runtime)
        assertEquals("deepseek/deepseek-v4-pro", piSdkDetail.model)
        assertEquals(RuntimeProviderConfig.PiBuiltin("deepseek", "pi-key"), piSdkDetail.runtimeProvider)

        val claudeClient =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody =
                        """{"id":"agent-3","name":"Claude","runtimeConfig":{"version":1,"runtime":"claude","provider":{"kind":"pi-builtin","providerId":"deepseek","apiKey":"pi-key"},"model":{"kind":"preset","id":"sonnet"},"mode":{"kind":"default"}}}"""
                )
            )
        val claudeDetail = assertIs<DataResult.Content<AgentSummary>>(claudeClient.agent(MemberId("agent-3"))).data

        assertEquals("claude", claudeDetail.runtime)
        assertNull(claudeDetail.runtimeProvider)
    }

    @Test
    fun typedAgentsApiRejectsGuessedWrappersAndAliases() = runPortableCoroutineTest {
        val client =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentsBody = """{"agents":[{"id":"agent-1"}]}""",
                    agentBody = """{"agent":{"id":"agent-1","name":"Noel"}}""",
                    commandBody = """{"success":true}""",
                    agentDmsBody = """{"dms":[{"id":"dm-1"}]}""",
                    activityBody = """{"activityLog":[{"id":"a1"}]}""",
                    workspaceFilesBody = """{"items":[{"path":"/repo/README.md"}]}""",
                    workspaceFileReadBody = """{"file":{"path":"/repo/README.md","content":"Hello"}}""",
                    skillsBody = """{"skills":{"global":[],"workspace":[]}}""",
                    scopesBody = """{"scopes":["messages:read"]}""",
                    bootstrapBody = """{"token":"raw","id":"token-1"}"""
                )
            )
        val agentId = MemberId("agent-1")

        assertEquals("agents_list_contract_mismatch", assertIs<DataResult.Error>(client.agents()).error.code)
        assertEquals("agent_detail_contract_mismatch", assertIs<DataResult.Error>(client.agent(agentId)).error.code)
        assertEquals("agent_start_contract_mismatch", assertIs<DataResult.Error>(client.startAgent(agentId)).error.code)
        assertEquals("agent_dms_contract_mismatch", assertIs<DataResult.Error>(client.agentDirectMessages(agentId)).error.code)
        assertEquals("agent_activity_log_contract_mismatch", assertIs<DataResult.Error>(client.agentActivityLog(agentId)).error.code)
        assertEquals("agent_workspace_files_contract_mismatch", assertIs<DataResult.Error>(client.agentWorkspaceFiles(agentId)).error.code)
        assertEquals("agent_workspace_file_read_contract_mismatch", assertIs<DataResult.Error>(client.readAgentWorkspaceFile(agentId, "/repo/README.md")).error.code)
        assertEquals("agent_skills_contract_mismatch", assertIs<DataResult.Error>(client.agentSkills(agentId)).error.code)
        assertEquals("agent_scopes_contract_mismatch", assertIs<DataResult.Error>(client.agentScopes(agentId)).error.code)
        assertEquals("agent_bootstrap_token_contract_mismatch", assertIs<DataResult.Error>(client.issueBootstrapToken(agentId, "{}")).error.code)
    }

    // ── task #517: agent archive section endpoints ──

    @Test
    fun agentArchiveMapsEnvelopeAndFields() = runPortableCoroutineTest {
        val api = RecordingAgentsApi()
        val client = RaftAgentsApiClient(api)

        val archive = assertIs<DataResult.Content<RaftAgentArchive?>>(
            client.agentArchive(MemberId("agent-1"))
        ).data

        assertEquals(listOf("GET agents/agent-1/archive"), api.calls)
        requireNotNull(archive)
        assertEquals("agent-1", archive.agentId)
        assertEquals("user-9", archive.archivedByUserId)
        assertEquals("Mini M4", archive.machineName)
        assertEquals(AgentArchiveWorkspaceState.OnComputer, archive.workspaceState)
        assertEquals(null, archive.pendingOp)
        assertEquals(false, archive.waitingForComputer)
        assertEquals(null, archive.lastErrorCode)
    }

    @Test
    fun agentArchiveNullEnvelopeMeansNoArchiveRecord() = runPortableCoroutineTest {
        val client = RaftAgentsApiClient(
            RecordingAgentsApi(archiveBody = """{"archive":null}""")
        )

        val result = assertIs<DataResult.Content<RaftAgentArchive?>>(
            client.agentArchive(MemberId("agent-1"))
        )

        assertEquals(null, result.data)
    }

    @Test
    fun agentArchiveUnknownWorkspaceStateCollapsesToUnknown() = runPortableCoroutineTest {
        val client = RaftAgentsApiClient(
            RecordingAgentsApi(
                archiveBody = """{"archive":{"agentId":"agent-1","workspaceState":"migrating"}}"""
            )
        )

        val archive = assertIs<DataResult.Content<RaftAgentArchive?>>(
            client.agentArchive(MemberId("agent-1"))
        ).data

        assertEquals(AgentArchiveWorkspaceState.Unknown, archive?.workspaceState)
    }

    @Test
    fun deleteAgentArchiveWorkspacePostsDeleteAndMapsUpdatedView() = runPortableCoroutineTest {
        val api = RecordingAgentsApi()
        val client = RaftAgentsApiClient(api)

        val archive = assertIs<DataResult.Content<RaftAgentArchive>>(
            client.deleteAgentArchiveWorkspace(MemberId("agent-1"))
        ).data

        assertEquals(
            listOf("""POST agents/agent-1/archive/workspace body={"action":"delete"}"""),
            api.calls
        )
        assertEquals(AgentArchivePendingOp.Delete, archive.pendingOp)
        assertEquals(true, archive.waitingForComputer)
    }

    @Test
    fun aRefusedStartKeepsTheServersCode() = runPortableCoroutineTest {
        // slock routes/agents.ts `POST /:id/start`: an assistant with no Computer, or one whose
        // Computer's key was revoked, is refused with 409 and a machine `code`.
        listOf("machine_unassigned", "computer_access_revoked").forEach { code ->
            val client = RaftAgentsApiClient(
                RecordingAgentsApi(startCode = 409, startErrorBody = """{"error":"Cannot start","code":"$code"}""")
            )
            val error = assertIs<DataResult.Error>(client.startAgent(MemberId("agent-1"))).error
            assertEquals("http_409", error.code)
            assertEquals(code, error.serverCode)
            assertEquals("Cannot start", error.serverMessage)
        }
    }

    private class RecordingAgentsApi(
        private val agentsBody: String = """[{"id":"agent-1","name":"Noel","status":"active"}]""",
        private val agentBody: String = """{"id":"agent-1","name":"Agent One","displayName":"Noel Bot","serverRole":"Admin","status":"active","model":"gpt-5","runtime":"codex","runtimeConfig":{"version":1,"runtime":"codex","model":{"kind":"preset","id":"gpt-5.6-sol"},"mode":{"kind":"default"}},"machineId":"machine-1","reasoningEffort":"medium","executionMode":"local","avatarUrl":"pixel:robot","creatorId":"user-1","creatorType":"human","creator":{"type":"human","id":"user-1","name":"artin","displayName":"Artin","avatarUrl":"pixel:user","gravatarHash":"hash-1"},"daemonVersion":"0.57.1","envVars":{"OPENAI_API_KEY":"configured","ANTHROPIC_API_KEY":"configured"},"createdAgents":[{"id":"agent-child","name":"helper","displayName":"Helper","runtime":"codex","status":"idle"}],"activity":"idle","activityDetail":"Ready","deletedAt":null}""",
        private val createAgentBody: String = """{"id":"agent-2","name":"Noel","status":"active","avatarUrl":"pixel:tree"}""",
        private val createAgentCode: Int = 200,
        // Mirrors the production transport shape for non-2xx responses:
        // when set, the create response carries the body ONLY in errorBodyText.
        private val createAgentErrorBody: String? = null,
        private val commandBody: String = """{"ok":true}""",
        private val agentDmsBody: String = """[{"id":"dm-1","name":"Agent chat"}]""",
        private val activityBody: String = """[{"id":"a1","type":"tool_start","timestamp":"2026-05-22T00:00:00.000Z"}]""",
        private val workspaceFilesBody: String = """{"files":[{"path":"/repo/README.md","name":"README.md"}]}""",
        private val workspaceFileReadBody: String = """{"path":"/repo/README.md","content":"Hello","binary":false,"size":5,"mimeType":"text/markdown","encoding":"utf-8","modifiedAt":"2026-05-22T00:00:00.000Z"}""",
        private val externalStatusBody: String = """{"setupState":"waiting_for_login","credentialLastUsedAt":null,"lastActivityAt":null}""",
        private val externalStatusCode: Int = 200,
        private val externalStatusErrorBody: String? = null,
        private val skillsBody: String = """{"global":[{"name":"openai-docs"}],"workspace":[{"name":"android-regression"}]}""",
        private val scopesBody: String = """{"agentId":"agent-1","granted":["messages:read","messages:send"],"revision":7,"updatedAt":"2026-05-22T00:00:00.000Z"}""",
        private val loginBody: String = """{"credentialId":"credential-1","apiKey":"sk_agent_secret","scopes":["messages:read"],"agentId":"agent-1","agentName":"Noel","serverId":"server-1"}""",
        private val bootstrapBody: String = """{"tokenId":"token-1","bootstrapToken":"sk_agent_secret","tokenPrefix":"sk_agent","ttlExpiresAt":"2026-05-22T01:00:00.000Z","scopes":["messages:read"],"agentId":"agent-1","agentName":"Noel","serverId":"server-1"}""",
        private val archiveBody: String = """{"archive":{"agentId":"agent-1","archivedAt":"2026-10-18T12:00:00.000Z","archivedByUserId":"user-9","machineId":"machine-1","machineName":"Mini M4","workspaceState":"on_computer","pendingOp":null,"waitingForComputer":false,"lastErrorCode":null,"lastErrorAt":null}}""",
        private val archiveDeleteBody: String = """{"archive":{"agentId":"agent-1","archivedAt":"2026-10-18T12:00:00.000Z","archivedByUserId":"user-9","machineId":"machine-1","machineName":"Mini M4","workspaceState":"on_computer","pendingOp":"delete","waitingForComputer":true,"lastErrorCode":null,"lastErrorAt":null}}""",
        private val archiveDeleteCode: Int = 200,
        private val archiveDeleteErrorBody: String? = null,
        private val startCode: Int = 200,
        private val startErrorBody: String? = null
    ) : RaftAgentsApi {
        val calls = mutableListOf<String>()

        override suspend fun agents(): HomeNetworkResponse {
            calls += "GET agents"
            return ok(agentsBody)
        }

        override suspend fun agent(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}"
            return ok(agentBody)
        }

        override suspend fun createAgent(body: String): HomeNetworkResponse {
            calls += "POST agents body=$body"
            return if (createAgentErrorBody != null) {
                HomeNetworkResponse(code = createAgentCode, bodyText = null, errorBodyText = createAgentErrorBody)
            } else {
                HomeNetworkResponse(code = createAgentCode, bodyText = createAgentBody)
            }
        }

        override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "PATCH agents/${agentId.value} body=$body"
            return ok(agentBody)
        }

        override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/start"
            if (startErrorBody != null) return HomeNetworkResponse(code = startCode, bodyText = null, errorBodyText = startErrorBody)
            return ok(commandBody)
        }

        override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/stop"
            return ok(commandBody)
        }

        override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/reset body=$body"
            return ok(commandBody)
        }

        override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "DELETE agents/${agentId.value} body=$body"
            return ok(commandBody)
        }

        override suspend fun agentArchive(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/archive"
            return ok(archiveBody)
        }

        override suspend fun deleteAgentArchiveWorkspace(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/archive/workspace body=$body"
            return if (archiveDeleteErrorBody != null || archiveDeleteCode !in 200..299) {
                HomeNetworkResponse(code = archiveDeleteCode, bodyText = null, errorBodyText = archiveDeleteErrorBody)
            } else {
                ok(archiveDeleteBody)
            }
        }

        override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/assign-machine body=$body"
            return ok(commandBody)
        }

        override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/agent-dms"
            return ok(agentDmsBody)
        }

        override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/activity-log limit=$limit"
            return ok(activityBody)
        }

        override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/workspace-files dirPath=$dirPath"
            return ok(workspaceFilesBody)
        }

        override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/workspace-files/read path=$path"
            return ok(workspaceFileReadBody)
        }

        override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/skills"
            return ok(skillsBody)
        }

        override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/external-status"
            return if (externalStatusCode in 200..299) {
                ok(externalStatusBody)
            } else {
                HomeNetworkResponse(code = externalStatusCode, bodyText = null, errorBodyText = externalStatusErrorBody)
            }
        }

        override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/scopes"
            return ok(scopesBody)
        }

        override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "PUT agents/${agentId.value}/scopes body=$body"
            return ok(scopesBody)
        }

        override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse {
            calls += "PUT agents/${agentId.value}/scopes body={\"mode\":\"default\"}"
            return ok(scopesBody)
        }

        override suspend fun issueAgentCredential(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/credentials body=$body"
            return ok(loginBody)
        }

        override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/bootstrap-tokens body=$body"
            return ok(bootstrapBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    // task #1119 PR 2: GET /agents/:id/external-status (React fetchExternalAgentStatus parity).
    @Test
    fun agentExternalStatusParsesSetupStateAndTimestamps() = runPortableCoroutineTest {
        val transport =
            RecordingAgentsApi(
                externalStatusBody =
                    """{"setupState":"connected","credentialLastUsedAt":"2026-09-03T05:00:00.000Z","lastActivityAt":"2026-09-03T06:00:00.000Z"}"""
            )
        val client = RaftAgentsApiClient(transport)

        val status = assertIs<DataResult.Content<ExternalAgentStatusSnapshot>>(client.agentExternalStatus(MemberId("agent-1"))).data

        assertEquals(ExternalAgentSetupState.Connected, status.setupState)
        assertEquals("2026-09-03T05:00:00.000Z", status.credentialLastUsedAt)
        assertEquals("2026-09-03T06:00:00.000Z", status.lastActivityAt)
        assertEquals(listOf("GET agents/agent-1/external-status"), transport.calls)
    }

    // 403 = caller lacks editAgents/creator authority; must surface as NoPermission,
    // never as a status (the UI treats it as "not provided", not "waiting for login").
    @Test
    fun agentExternalStatusForbiddenIsNoPermissionNotAStatus() = runPortableCoroutineTest {
        val client =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    externalStatusCode = 403,
                    externalStatusErrorBody = """{"error":"The `editAgents` capability or human creator authority is required to view external setup status"}"""
                )
            )

        val result = client.agentExternalStatus(MemberId("agent-1"))
        assertTrue(result is DataResult.NoPermission, "403 must map to NoPermission, got $result")
    }

    @Test
    fun agentSummaryCarriesExternalFromBooleanOrRuntime() = runPortableCoroutineTest {
        val byBoolean =
            RaftAgentsApiClient(RecordingAgentsApi(agentBody = """{"id":"agent-1","name":"bridge","status":"active","runtime":"claude","external":true}"""))
        val byRuntime =
            RaftAgentsApiClient(RecordingAgentsApi(agentBody = """{"id":"agent-1","name":"bridge","status":"active","runtime":"external"}"""))
        val managed =
            RaftAgentsApiClient(RecordingAgentsApi(agentBody = """{"id":"agent-1","name":"claude","status":"active","runtime":"claude"}"""))

        assertEquals(true, assertIs<DataResult.Content<AgentSummary>>(byBoolean.agent(MemberId("agent-1"))).data.isExternal)
        assertEquals(true, assertIs<DataResult.Content<AgentSummary>>(byRuntime.agent(MemberId("agent-1"))).data.isExternal)
        assertEquals(false, assertIs<DataResult.Content<AgentSummary>>(managed.agent(MemberId("agent-1"))).data.isExternal)
        assertEquals(true, assertIs<DataResult.Content<AgentSummary>>(byRuntime.agent(MemberId("agent-1"))).data.toAgentDetailSummary().isExternal)
    }

    @Test
    fun agentSummaryPrefersActivityKindOverActivityAndKeepsErrorCode() = runPortableCoroutineTest {
        val kindWins =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody = """{"id":"agent-1","name":"bot","status":"active","activity":"offline","activityKind":"online"}"""
                )
            )
        val kindError =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody = """{"id":"agent-2","name":"bot","status":"active","activityKind":"error","activityErrorCode":"usage_limit"}"""
                )
            )
        val blankKindFallsBack =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody = """{"id":"agent-3","name":"bot","status":"active","activity":"working","activityKind":"  "}"""
                )
            )
        val blankKindKeepsErrorCode =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody = """{"id":"agent-4","name":"bot","status":"active","activity":"error","activityKind":"   ","activityErrorCode":"usage_limit"}"""
                )
            )
        val kindOverridesErrorDropsCode =
            RaftAgentsApiClient(
                RecordingAgentsApi(
                    agentBody = """{"id":"agent-5","name":"bot","status":"active","activity":"error","activityKind":"online","activityErrorCode":"usage_limit"}"""
                )
            )

        assertEquals("online", assertIs<DataResult.Content<AgentSummary>>(kindWins.agent(MemberId("agent-1"))).data.activity)
        val errorSummary = assertIs<DataResult.Content<AgentSummary>>(kindError.agent(MemberId("agent-2"))).data
        assertEquals("error", errorSummary.activity)
        assertEquals(AgentRuntimeErrorCode.UsageLimit, errorSummary.activityErrorCode)
        assertEquals("working", assertIs<DataResult.Content<AgentSummary>>(blankKindFallsBack.agent(MemberId("agent-3"))).data.activity)
        val blankKindErrorSummary = assertIs<DataResult.Content<AgentSummary>>(blankKindKeepsErrorCode.agent(MemberId("agent-4"))).data
        assertEquals("error", blankKindErrorSummary.activity)
        assertEquals(AgentRuntimeErrorCode.UsageLimit, blankKindErrorSummary.activityErrorCode)
        val nonErrorOverride = assertIs<DataResult.Content<AgentSummary>>(kindOverridesErrorDropsCode.agent(MemberId("agent-5"))).data
        assertEquals("online", nonErrorOverride.activity)
        assertEquals(null, nonErrorOverride.activityErrorCode)
    }

    /**
     * The profile's Mode row reads the saved `runtimeConfig.mode` (web `hydrateRuntimeConfigForm`), the
     * value the edit form's Fast mode starts from. `executionMode` says where the agent runs (byoc /
     * cloud) and is never the mode. No saved config, no mode in it, or another version: none (Default).
     * Bodies as `withAgentProjection` sends them: the hydrated config of a Codex agent.
     */
    @Test
    fun agentSummaryReadsTheSavedRuntimeModeNotTheExecutionMode() = runPortableCoroutineTest {
        suspend fun read(runtimeConfig: String?, executionMode: String = "byoc"): AgentSummary {
            val config = runtimeConfig?.let { ""","runtimeConfig":$it""" }.orEmpty()
            val body =
                """{"id":"agent-1","name":"codex-agent","status":"active","runtime":"codex","model":"gpt-6.2-astra","reasoningEffort":"max","executionMode":"$executionMode"$config}"""
            return assertIs<DataResult.Content<AgentSummary>>(RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-1"))).data
        }
        fun saved(mode: String?, version: Int = 1): String {
            val modeField = mode?.let { ""","mode":$it""" }.orEmpty()
            return """{"version":$version,"runtime":"codex","model":{"kind":"preset","id":"gpt-6.2-astra"},"reasoningEffort":"max","envVars":null$modeField}"""
        }
        val cases =
            listOf(
                Triple("fast", read(saved("""{"kind":"fast"}""")), "fast"),
                Triple("fast on a named Codex tier", read(saved("""{"kind":"fast","serviceTier":"ultrafast"}""")), "fast"),
                Triple("default on a cloud agent", read(saved("""{"kind":"default"}"""), executionMode = "cloud"), "default"),
                Triple("a kind this client does not know", read(saved("""{"kind":"turbo"}""")), "default"),
                Triple("no mode in the saved config", read(saved(null)), null),
                Triple("a saved config of another version", read(saved("""{"kind":"fast"}""", version = 2)), null),
                Triple("no runtime config (a viewer who may not edit the agent)", read(null), null),
            )
        for ((name, summary, expected) in cases) {
            assertEquals(expected, summary.runtimeMode, name)
            assertEquals(expected, summary.toAgentDetailSummary().runtimeMode, name)
            assertTrue(summary.executionMode == "byoc" || summary.executionMode == "cloud", "$name: executionMode is where it runs")
        }
    }

    @Test
    fun agentSummaryAndDetailParseRunningWorkersDefensively() = runPortableCoroutineTest {
        suspend fun readWorkers(rawField: String?): Pair<Int?, Int?> {
            val extra = rawField?.let { ""","runningWorkers":$it""" }.orEmpty()
            val body = """{"id":"agent-1","name":"kirby","status":"active","activity":"online"$extra}"""
            val summary =
                assertIs<DataResult.Content<AgentSummary>>(
                    RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-1"))
                ).data
            return summary.runningWorkers to summary.toAgentDetailSummary().runningWorkers
        }
        assertEquals(3 to 3, readWorkers("3"))
        assertEquals(1 to 1, readWorkers("1"))
        assertEquals(null to null, readWorkers(null))
        assertEquals(null to null, readWorkers("0"))
        assertEquals(null to null, readWorkers("-2"))
        assertEquals(null to null, readWorkers("1.5"))
        assertEquals(null to null, readWorkers(""""2""""))
    }

    @Test
    fun agentSummaryDetailAndRosterParseServiceTierDefensively() = runPortableCoroutineTest {
        suspend fun readTier(rawField: String?): Pair<String?, String?> {
            val extra = rawField?.let { ""","serviceTier":$it""" }.orEmpty()
            val body = """{"id":"agent-1","name":"otter","status":"active","activity":"online"$extra}"""
            val summary =
                assertIs<DataResult.Content<AgentSummary>>(
                    RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-1"))
                ).data
            return summary.serviceTier to summary.toAgentDetailSummary().serviceTier
        }
        assertEquals("default" to "default", readTier(""""default""""))
        assertEquals("priority" to "priority", readTier(""""priority""""))
        assertEquals("flex" to "flex", readTier(""""flex""""))
        assertEquals(null to null, readTier(null))
        assertEquals(null to null, readTier(""""""""))
        assertEquals(null to null, readTier(""""INVALID TIER!""""))
        assertEquals(null to null, readTier("123"))

        val roster =
            parseSharedAgentProfile(
                """{"id":"agent-1","name":"otter","runtime":"codex","model":"gpt-6.2-sol","status":"active","activity":"online","serviceTier":"default"}"""
            )
        assertEquals("default", roster?.agentServiceTier)
        val rosterInvalid =
            parseSharedAgentProfile(
                """{"id":"agent-1","name":"otter","runtime":"codex","model":"gpt-6.2-sol","status":"active","activity":"online","serviceTier":"BAD TIER"}"""
            )
        assertEquals(null, rosterInvalid?.agentServiceTier)
    }

    @Test
    fun agentSummaryAndDetailParseAssistantPauseMetadata() = runPortableCoroutineTest {
        suspend fun readAgent(extraFields: String): Pair<AgentSummary, AgentDetailSummary> {
            val body = """{"id":"agent-1","name":"alice-assistant","status":"active","assistantOwnerUserId":"user-2"$extraFields}"""
            val summary =
                assertIs<DataResult.Content<AgentSummary>>(
                    RaftAgentsApiClient(RecordingAgentsApi(agentBody = body)).agent(MemberId("agent-1"))
                ).data
            return summary to summary.toAgentDetailSummary()
        }

        val (pausedSummary, pausedDetail) =
            readAgent(""","assistantPausedAt":"2026-11-28T10:00:00Z","assistantPausedBy":{"id":"u-admin","name":"Grace"}""")
        assertEquals("2026-11-28T10:00:00Z", pausedSummary.assistantPausedAt)
        assertEquals("Grace", pausedSummary.assistantPausedByName)
        assertEquals("2026-11-28T10:00:00Z", pausedDetail.assistantPausedAt)
        assertEquals("Grace", pausedDetail.assistantPausedByName)

        // Member view: `assistantPausedAt` is present without `assistantPausedBy`.
        val (memberSummary, memberDetail) =
            readAgent(""","assistantPausedAt":"2026-11-28T10:00:00Z" """)
        assertEquals("2026-11-28T10:00:00Z", memberSummary.assistantPausedAt)
        assertNull(memberSummary.assistantPausedByName)
        assertEquals("2026-11-28T10:00:00Z", memberDetail.assistantPausedAt)
        assertNull(memberDetail.assistantPausedByName)

        // Stray `assistantPausedBy` without `assistantPausedAt` must not surface a stale name.
        val (unpausedSummary, unpausedDetail) =
            readAgent(""","assistantPausedBy":{"id":"u-admin","name":"Grace"}""")
        assertNull(unpausedSummary.assistantPausedAt)
        assertNull(unpausedSummary.assistantPausedByName)
        assertNull(unpausedDetail.assistantPausedAt)
        assertNull(unpausedDetail.assistantPausedByName)
    }
}
