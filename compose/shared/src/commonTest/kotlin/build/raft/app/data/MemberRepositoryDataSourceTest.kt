package build.raft.app.data

import build.raft.app.SlockFilePreviewPayload
import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.withAgentWorkspaceLoadError
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class MemberRepositoryDataSourceTest {
    @Test
    fun emptyChannelRosterPreservesCanonicalAddPermission() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                channelMembersResponse = HomeNetworkResponse(200, """{"humans":[],"agents":[]}""")
                channelDetailResponse = HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":false}}""")
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val result =
            assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                repository.getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty"))
            )

        assertEquals(emptyList(), result.data.members)
        assertTrue(result.data.canAddMembers)
    }

    @Test
    fun rosterDoesNotGrantMutationsWithoutMatchingChannelCapabilities() = runBlocking {
        // botiverse/slock 1443178ec2c72422256ea05f7d6fd5e480429e0a:
        // routes/channels.ts attaches channelCapabilities to GET /:id, not /:id/members.
        val cases = listOf(
            HomeNetworkResponse(503, "{}"),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel"}"""),
            HomeNetworkResponse(200, """{"id":"another","name":"design","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"all","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel","archivedAt":"2026-09-30T00:00:00Z","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
        )
        for (response in cases) {
            val network = FakeMemberNetworkClient().apply {
                channelMembersResponse = HomeNetworkResponse(200, """{"humans":[{"id":"user-1","name":"Alex","effectiveChannelRole":"admin"}],"agents":[]}""")
                channelDetailResponse = response
            }
            val result = assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                RealMemberRepository(sampleStore(), network).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")))
            assertEquals("admin", result.data.members.single().effectiveChannelRole)
            assertFalse(result.data.canAddMembers)
            assertFalse(result.data.canRemoveMembers)
        }
    }

    @Test
    fun explicitServerMembersLoadDoesNotRequireSelectedServerToAlreadySwitch() = runBlocking {
        val session =
            sampleSession().copy(
                servers =
                    sampleSession().servers +
                        StoredAuthServer(
                            id = "server-2",
                            name = "Second Server",
                            slug = "second-server",
                            ownerId = "user-1",
                            onboardingAgentId = null,
                            plan = "team",
                            planDowngradedAt = null,
                            role = "member",
                            createdAt = "2026-07-01T00:00:00Z"
                        )
            )
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(session), network)

        val result = repository.getMembers(DataRequestPriority.Visible, ServerId("server-2"))

        assertTrue(result is DataResult.Content)
        assertEquals("server-2", result.data.server.id.value)
        assertEquals("Second Server", result.data.server.name)
        assertTrue(result.data.members.any { it.id.value == "user-2" })
        assertTrue(network.calls.contains("GET servers/server-2/members server=server-2"))
        assertTrue(network.calls.contains("GET agents server=server-2"))
        assertTrue(network.calls.contains("GET servers/server-2/machines server=server-2"))
    }

    @Test
    fun agentDetailIncludesTypedSummaryAndLifecycleActions() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val detail = repository.getAgentDetail(DataRequestPriority.Visible, agentId)
        val start = repository.startAgent(DataRequestPriority.Foreground, agentId)
        val stop = repository.stopAgent(DataRequestPriority.Foreground, agentId)
        val reset = repository.resetAgent(DataRequestPriority.Foreground, AgentResetRequest(agentId, AgentResetMode.Restart))
        val assign = repository.assignAgentMachine(
            DataRequestPriority.Foreground,
            AgentMachineAssignmentRequest(agentId, "machine-2")
        )
        val delete = repository.deleteAgent(DataRequestPriority.Foreground, agentId)

        assertTrue(detail is DataResult.Content)
        assertEquals("agent-1", detail.data.summary?.id)
        assertEquals("codex", detail.data.summary?.runtime)
        assertEquals("gpt-5", detail.data.summary?.model)
        assertEquals("medium", detail.data.summary?.reasoning)
        assertEquals("local", detail.data.summary?.executionMode)
        assertEquals("machine-1", detail.data.summary?.machineId)
        assertEquals("Mac Studio", detail.data.summary?.machineName)
        assertEquals("0.57.1", detail.data.summary?.daemonVersion)
        assertEquals("user-1", detail.data.summary?.creatorId)
        assertEquals("human", detail.data.summary?.creatorType)
        assertEquals("Artin", detail.data.summary?.creator?.displayName)
        assertEquals("artin", detail.data.summary?.creator?.name)
        assertEquals("pixel:user", detail.data.summary?.creator?.avatarUrl)
        assertEquals(listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY"), detail.data.summary?.envVarNames)
        assertEquals("Helper", detail.data.summary?.createdAgents?.single()?.displayName)
        assertEquals("idle", detail.data.summary?.activity)
        assertEquals(1, detail.data.directMessages.size)
        assertEquals(2, detail.data.channels.size)
        assertTrue(detail.data.channels.last().activityMuted)
        assertEquals(7L, detail.data.channels.last().muteFromSeq)
        assertEquals(1, detail.data.reminders.size)
        assertEquals(true, detail.data.skills.groups.isNotEmpty())

        assertTrue(start is DataResult.Content)
        assertEquals("start", start.data.action)
        assertTrue(stop is DataResult.Content)
        assertEquals("stop", stop.data.action)
        assertTrue(reset is DataResult.Content)
        assertEquals(AgentResetMode.Restart, reset.data.resetMode)
        assertTrue(assign is DataResult.Content)
        assertEquals("machine-2", assign.data.machineId)
        assertTrue(delete is DataResult.Content)
        assertEquals("delete", delete.data.action)

        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/agent-dms") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/channels") })
        assertTrue(network.requests.any { it.matches("GET", "reminders") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/activity-log") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/skills") })
        assertTrue(network.requests.any { it.matches("GET", "integrations/agents/agent-1") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/start") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/stop") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/reset") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/assign-machine") })
        assertTrue(network.requests.any { it.matches("DELETE", "agents/agent-1") })
    }

    @Test
    fun agentDetailStartsIndependentSectionsTogetherWithoutFullAgentsFallback() = runBlocking {
        val network = FakeMemberNetworkClient()
        val gate = CompletableDeferred<Unit>()
        network.detailRequestGate = gate
        val repository = RealMemberRepository(sampleStore(), network)

        val result = async {
            repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))
        }

        try {
            network.firstDetailRequestStarted.await()
            repeat(FakeMemberNetworkClient.expectedDetailRequestStarts.size) {
                yield()
            }
            assertEquals(FakeMemberNetworkClient.expectedDetailRequestStarts, network.detailRequestsStarted)
            assertFalse(network.requests.any { it.matches("GET", "agents") })
        } finally {
            gate.complete(Unit)
        }
        assertIs<DataResult.Content<AgentDetailSnapshot>>(result.await())
        Unit
    }

    @Test
    fun agentDetailCurrentActivityComesFromSingleAgentSummary() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                agentSummaryActivity = "working"
                agentSummaryActivityDetail = "Compiling focused tests"
                agentsListActivity = "error"
                agentsListActivityDetail = "Wrong full-list authority"
                activityLogBody = "[]"
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val detail = assertIs<DataResult.Content<AgentDetailSnapshot>>(
            repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))
        ).data

        assertTrue(
            detail.activity.any {
                it.statusActivity == "working" && it.body == "Compiling focused tests"
            }
        )
        assertFalse(detail.activity.any { it.statusActivity == "error" })
        assertFalse(network.requests.any { it.matches("GET", "agents") })
    }

    @Test
    fun agentDetailSummaryRemainsTheOnlyRequiredSection() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                agentSummaryResponse = HomeNetworkResponse(code = 503, bodyText = """{"error":"unavailable"}""")
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val result = repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))

        assertEquals("server_unavailable", assertIs<DataResult.Error>(result).error.code)
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/agent-dms") })
        assertTrue(network.requests.any { it.matches("GET", "integrations/agents/agent-1") })
    }

    @Test
    fun agentActivityDoesNotReusePreviousRepositoryResults() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val first = repository.getAgentActivity(DataRequestPriority.Visible, agentId)
        assertTrue(first is DataResult.Content)
        assertTrue(first.data.activity.any { it.body == "Running task" })

        network.activityLogBody =
            """
            [{"timestamp":1710000999,"entry":{"kind":"agent_status","activity":"idle","detail":"After reset"}}]
            """.trimIndent()
        val merged = repository.getAgentActivity(DataRequestPriority.Visible, agentId)
        assertTrue(merged is DataResult.Content)
        assertTrue(!merged.data.activity.any { it.body == "Running task" })
        assertTrue(merged.data.activity.any { it.body == "After reset" })
    }

    @Test
    fun agentDetailKeepsProfileWhenOptionalSectionFails() = runBlocking {
        val network = FakeMemberNetworkClient().apply {
            skillsResponse = HomeNetworkResponse(code = 503, bodyText = """{"code":"server_unavailable"}""")
        }
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val detail = repository.getAgentDetail(DataRequestPriority.Visible, agentId)

        assertTrue(detail is DataResult.Content)
        assertEquals("agent-1", detail.data.summary?.id)
        assertEquals("Noel Bot", detail.data.summary?.displayName)
        assertEquals(emptyList(), detail.data.skills.groups)
    }

    @Test
    fun agentDetailRetainsCompleteIntegrationErrorForVisibleCopy() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                agentIntegrationsResponse =
                    HomeNetworkResponse(
                        code = 500,
                        bodyText = null,
                        errorBodyText = """{"error":"Failed to load agent integrations"}"""
                    )
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val detail = assertIs<DataResult.Content<AgentDetailSnapshot>>(
            repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))
        ).data

        assertEquals(AgentAppLoginLoadState.Error, detail.appLogins.loadState)
        assertEquals("server_unavailable", detail.appLogins.errorCode)
        assertEquals("server_unavailable", detail.appLogins.screenError?.code)
        assertEquals(
            "Failed to load agent integrations",
            detail.appLogins.screenError?.serverMessage
        )
    }

    @Test
    fun agentLifecycleDuplicateSubmitIsGuardedInRepository() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")
        val blocker = CompletableDeferred<Unit>()
        network.startBlocker = blocker

        val first = async { repository.startAgent(DataRequestPriority.Foreground, agentId) }
        withTimeout(1_000) {
            while (!network.calls.contains("POST agents/agent-1/start")) {
                yield()
            }
        }

        val duplicate = repository.stopAgent(DataRequestPriority.Foreground, agentId)
        assertTrue(duplicate is DataResult.Error)
        assertEquals("agent_stop_in_flight", duplicate.error.code)

        blocker.complete(Unit)
        assertTrue(first.await() is DataResult.Content)
    }

    @Test
    fun workspaceReadErrorsPreserveOnlyStructuredServerErrorText() = runBlocking {
        val fixtures =
            listOf(
                "structured" to ("""{"error":"path query parameter is required"}""" to "path query parameter is required"),
                "wrong-alias" to ("""{"message":"path query parameter is required"}""" to null),
                "malformed" to ("""{"error":"unterminated""" to null),
                "plain-text" to ("path query parameter is required" to null),
                "empty" to ("" to null)
            )

        fixtures.forEach { (name, fixture) ->
            val (body, expectedMessage) = fixture
            val network =
                FakeMemberNetworkClient().apply {
                    workspaceFileResponse = HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = body)
                }
            val repository = RealMemberRepository(sampleStore(), network)

            val error =
                assertIs<DataResult.Error>(
                    repository.readAgentWorkspaceFile(
                        DataRequestPriority.Visible,
                        MemberId("agent-1"),
                        "/repo/README.md"
                    ),
                    name
                ).error

            assertEquals("http_400", error.code, name)
            assertEquals(expectedMessage, error.serverMessage, name)
        }
    }

    @Test
    fun workspacePermissionErrorsPreserveExactServerErrorTextOn401And403() = runBlocking {
        listOf(401, 403).forEach { status ->
            val exactReason = "  member-route-$status 理由  "
            val network =
                FakeMemberNetworkClient().apply {
                    workspaceFileResponse =
                        HomeNetworkResponse(
                            code = status,
                            bodyText = null,
                            errorBodyText = """{"error":"$exactReason"}"""
                        )
                }
            val repository = RealMemberRepository(sampleStore(), network)

            val blocker =
                assertIs<DataResult.NoPermission>(
                    repository.readAgentWorkspaceFile(
                        DataRequestPriority.Visible,
                        MemberId("agent-1"),
                        "/repo/README.md"
                    )
                ).blocker

            assertEquals(exactReason, blocker.serverMessage)
        }
    }

    @Test
    fun workspaceStructuredServerErrorReachesFilePreviewVisibleCopy() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                workspaceFileResponse =
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText = """{"error":"path query parameter is required"}"""
                    )
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val error =
            assertIs<DataResult.Error>(
                repository.readAgentWorkspaceFile(
                    DataRequestPriority.Visible,
                    MemberId("agent-1"),
                    "/repo/README.md"
                )
            ).error
        val payload =
            SlockFilePreviewPayload().withAgentWorkspaceLoadError(
                error,
                englishErrorPresentationStringsForTest()
            )

        assertEquals("path query parameter is required", payload.message)
        assertEquals("http_400", payload.error)
    }

    @Test
    fun agentProfileUpdateBodyKeepsAvatarChangesExplicit() {
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = " Noel Bot ",
                    description = " "
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":"Runs tests","avatarUrl":"pixel:robot"}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = " Runs tests ",
                    avatarUrl = " pixel:robot ",
                    includeAvatarUrl = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"avatarUrl":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    avatarUrl = null,
                    includeAvatarUrl = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"serverRole":"admin"}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    serverRole = " Admin ",
                    includeServerRole = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"serverRole":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    serverRole = " ",
                    includeServerRole = true
                )
            )
        )
    }

    @Test
    fun builtInRuntimeUpdateOmitsOnlyExplicitlyRetainedWriteOnlyKey() {
        val config =
            RuntimeConfig(
                runtime = "builtin",
                provider = RuntimeProviderConfig.Preset("deepseek", "replacement-secret"),
                model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro")
            )

        val retainedBody =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig = config.copy(
                        provider = RuntimeProviderConfig.Preset("deepseek", "")
                    ),
                    includeRuntimeConfig = true,
                    retainOmittedBuiltInProviderSecret = true
                )
            )
        assertTrue("\"providerId\":\"deepseek\"" in retainedBody)
        assertFalse("\"apiKey\"" in retainedBody)

        val replacedBody =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig = config,
                    includeRuntimeConfig = true
                )
            )
        assertTrue("\"apiKey\":\"replacement-secret\"" in replacedBody)
    }

    @Test
    fun nonBuiltInRuntimeUpdateOmitsNullOptionalRuntimeConfigFields() {
        // task #152: `"loadLocalPlugins":null` (and other explicit nulls) inside runtimeConfig
        // were rejected by the server's `!== undefined` validation, 400ing every save.
        val body =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig = RuntimeConfig(
                        runtime = "claude",
                        model = RuntimeModelConfig.Preset("opus")
                    ),
                    includeRuntimeConfig = true
                )
            )
        val runtimeConfig = Json.parseToJsonElement(body).jsonObject.getValue("runtimeConfig").jsonObject
        assertFalse("loadLocalPlugins" in runtimeConfig, body)
        assertFalse("hostUserState" in runtimeConfig, body)
        assertFalse("provider" in runtimeConfig, body)
        assertFalse("command" in runtimeConfig, body)
        assertFalse(runtimeConfig.values.any { it is JsonNull }, body)
        assertTrue("\"runtime\":\"claude\"" in body)
    }

    @Test
    fun builtInRuntimeUpdateKeepsBooleanLoadLocalPlugins() {
        val body =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig = RuntimeConfig(
                        runtime = "builtin",
                        provider = RuntimeProviderConfig.Preset("deepseek", "secret"),
                        model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro"),
                        loadLocalPlugins = false
                    ),
                    includeRuntimeConfig = true
                )
            )
        val runtimeConfig = Json.parseToJsonElement(body).jsonObject.getValue("runtimeConfig").jsonObject
        assertEquals(JsonPrimitive(false), runtimeConfig["loadLocalPlugins"], body)
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val serverId: String?,
        val bodyText: String?
    ) {
        fun matches(expectedMethod: String, expectedPath: String): Boolean =
            method == expectedMethod && path == expectedPath
    }

    private class FakeMemberNetworkClient : SlockNetworkClient {
        companion object {
            val expectedDetailRequestStarts =
                setOf(
                    "GET agents/agent-1",
                    "GET agents/agent-1/agent-dms",
                    "GET agents/agent-1/channels",
                    "GET reminders",
                    "GET agents/agent-1/activity-log",
                    "GET agents/agent-1/skills",
                    "GET integrations/agents/agent-1"
                )
        }

        val calls = mutableListOf<String>()
        val requests = mutableListOf<RecordedRequest>()
        val detailRequestsStarted = linkedSetOf<String>()
        val firstDetailRequestStarted = CompletableDeferred<Unit>()
        var detailRequestGate: CompletableDeferred<Unit>? = null
        var activityLogBody: String =
            """
            [{"timestamp":1710000000,"entry":{"kind":"agent_status","activity":"working","detail":"Running task"}}]
            """.trimIndent()
        var skillsResponse: HomeNetworkResponse? = null
        var workspaceFileResponse: HomeNetworkResponse? = null
        var agentIntegrationsResponse: HomeNetworkResponse? = null
        var agentSummaryResponse: HomeNetworkResponse? = null
        var agentSummaryActivity: String = "idle"
        var agentSummaryActivityDetail: String = "Ready"
        var agentsListActivity: String = "idle"
        var agentsListActivityDetail: String = "Ready"
        var channelMembersResponse: HomeNetworkResponse? = null
        var channelDetailResponse: HomeNetworkResponse = HomeNetworkResponse(403, "{}")
        var startBlocker: CompletableDeferred<Unit>? = null

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: build.raft.app.model.ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            requests +=
                RecordedRequest(
                    method = method,
                    path = normalized,
                    query = query.toMap(),
                    serverId = serverIdOverride?.value,
                    bodyText = bodyText
                )
            val requestStart = "$method $normalized"
            if (requestStart in expectedDetailRequestStarts) {
                detailRequestsStarted += requestStart
                firstDetailRequestStarted.complete(Unit)
                detailRequestGate?.await()
            }
            return when {
                normalized == "agents/agent-1" && method == "GET" -> {
                    calls += "GET agents/agent-1"
                    agentSummaryResponse?.let { return it }
                    ok(
                        """
                        {
                          "id":"agent-1",
                          "name":"noel",
                          "displayName":"Noel Bot",
                          "status":"active",
                          "activity":"$agentSummaryActivity",
                          "activityDetail":"$agentSummaryActivityDetail",
                          "creatorId":"user-1",
                          "creatorType":"human",
                          "creator":{
                            "type":"human",
                            "id":"user-1",
                            "name":"artin",
                            "displayName":"Artin",
                            "avatarUrl":"pixel:user",
                            "gravatarHash":"hash-1"
                          },
                          "daemonVersion":"0.57.1",
                          "envVars":{"OPENAI_API_KEY":"configured","ANTHROPIC_API_KEY":"configured"},
                          "createdAgents":[{"id":"agent-child","name":"helper","displayName":"Helper","runtime":"codex","status":"idle"}],
                          "runtimeProfile":{"current":{"machineId":"machine-1","machineName":"Mac Studio","runtime":"codex","model":"gpt-5","reasoningEffort":"medium","executionMode":"local"}}
                        }
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/agent-dms" && method == "GET" -> {
                    calls += "GET agents/agent-1/agent-dms"
                    ok("""[{"id":"dm-1","peerId":"user-1","peerName":"Artin"}]""")
                }
                normalized == "agents/agent-1/channels" && method == "GET" -> {
                    calls += "GET agents/agent-1/channels"
                    ok(
                        """
                        [
                          {"id":"channel-1","name":"general","description":"Team updates","type":"channel","createdAt":"2026-06-01T00:00:00Z","archivedAt":null,"activityMuted":false,"muteFromSeq":null},
                          {"id":"channel-2","name":"private-ops","description":null,"type":"private","createdAt":"2026-06-02T00:00:00Z","archivedAt":null,"activityMuted":true,"muteFromSeq":7}
                        ]
                        """.trimIndent()
                    )
                }
                normalized == "reminders" && method == "GET" -> {
                    val queryMap = query.toMap()
                    calls += "GET reminders ownerAgentId=${queryMap["ownerAgentId"]} status=${queryMap["status"]}"
                    ok(
                        """
                        {"reminders":[{"reminderId":"reminder-1","ownerAgentId":"agent-1","title":"Ping","fireAt":"2026-05-27T00:00:00Z","status":"scheduled"}]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/activity-log" && method == "GET" -> {
                    calls += "GET agents/agent-1/activity-log limit=${query.toMap()["limit"]}"
                    ok(activityLogBody)
                }
                normalized == "agents" && method == "GET" -> {
                    calls += serverIdOverride?.let { "GET agents server=${it.value}" } ?: "GET agents"
                    ok("""[{"id":"agent-1","name":"noel","status":"active","activity":"$agentsListActivity","activityDetail":"$agentsListActivityDetail"}]""")
                }
                normalized == "servers/server-2/members" && method == "GET" -> {
                    calls += "GET servers/server-2/members server=${serverIdOverride?.value}"
                    ok("""{"members":[{"userId":"user-2","name":"second-user","displayName":"Second User"}]}""")
                }
                normalized == "servers/server-2/machines" && method == "GET" -> {
                    calls += "GET servers/server-2/machines server=${serverIdOverride?.value}"
                    ok("[]")
                }
                normalized == "agents/agent-1/skills" && method == "GET" -> {
                    calls += "GET agents/agent-1/skills"
                    skillsResponse?.let { return it }
                    ok(
                        """
                        {"global":[{"name":"docs","displayName":"Docs","description":"Reads docs"}],"workspace":[]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/workspace-files/read" && method == "GET" -> {
                    calls += "GET agents/agent-1/workspace-files/read path=${query.toMap()["path"]}"
                    workspaceFileResponse
                        ?: ok(
                            """{"path":"/repo/README.md","content":"# Readme","binary":false,"size":8,"mimeType":"text/markdown","encoding":"utf8"}"""
                        )
                }
                normalized == "integrations/agents/agent-1" && method == "GET" -> {
                    calls += "GET integrations/agents/agent-1"
                    agentIntegrationsResponse ?: ok("[]")
                }
                normalized == "agents/agent-1/start" && method == "POST" -> {
                    calls += "POST agents/agent-1/start"
                    startBlocker?.await()
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/stop" && method == "POST" -> {
                    calls += "POST agents/agent-1/stop"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/reset" && method == "POST" -> {
                    calls += "POST agents/agent-1/reset body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/assign-machine" && method == "POST" -> {
                    calls += "POST agents/agent-1/assign-machine body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1" && method == "DELETE" -> {
                    calls += "DELETE agents/agent-1"
                    ok("""{"ok":true}""")
                }
                normalized == "channels/channel-empty" && method == "GET" -> channelDetailResponse
                normalized == "channels/channel-empty/members" && method == "GET" ->
                    channelMembersResponse ?: error("Missing channel members response")
                else -> error("Unhandled request $method $normalized query=$query")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private fun sampleStore(session: StoredAuthSession = sampleSession()): SlockAuthSessionStore =
        InMemorySlockAuthSessionStore(session)

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    name = "User",
                    displayName = "Slock User",
                    description = null,
                    avatarUrl = null,
                    emailVerified = true
                ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers =
                listOf(
                    StoredAuthServer(
                        id = "server-1",
                        name = "Slock Android",
                        slug = "slock-android",
                        ownerId = "user-1",
                        onboardingAgentId = "agent-1",
                        plan = "team",
                        planDowngradedAt = null,
                        role = "owner",
                        createdAt = "2026-05-06T00:00:00Z"
                    )
                )
        )
}
