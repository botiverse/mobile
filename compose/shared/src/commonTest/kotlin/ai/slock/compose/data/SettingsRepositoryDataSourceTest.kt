package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsRepositoryDataSourceTest {
    @Test
    fun accountMapsProfileAndConnectedAccounts() = runBlocking {
        val repository =
            RealSettingsRepository(
                sessionStore = sampleStore(),
                networkClient =
                    FakeSettingsNetworkClient(
                        accountJson = """
                            {
                              "id": "user-1",
                              "email": "user@slock.ai",
                              "name": "slock-user",
                              "displayName": "Slock User",
                              "emailVerified": true
                            }
                        """.trimIndent()
                    )
            )

        val result = repository.getAccount()

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals("Slock User", snapshot.profile.displayName)
        assertEquals("slock-user", snapshot.profile.handle)
        assertEquals(true, snapshot.profile.verified)
        assertEquals("Owner", snapshot.profile.roleLabel)
        assertEquals("Email password", snapshot.connectedAccounts.single().provider)
    }

    @Test
    fun updateProfileReturnsAccountSnapshotAndSharedBody() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                accountJson = """{"id":"user-1","email":"new@slock.ai","name":"new","displayName":"New Name","emailVerified":true}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val account = repository.updateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai"))

        assertTrue(account is DataResult.Content)
        assertEquals("New Name", account.data.profile.displayName)
        assertEquals("""{"displayName":"New Name","handle":"@new","email":"new@slock.ai"}""", network.accountUpdateBodies.single())
    }

    @Test
    fun billingMapsReadablePlanStateAndRequiresOwnerPermission() = runBlocking {
        val billing = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient()).getBilling()
        val denied =
            RealSettingsRepository(
                sampleStore(
                    sampleSession().copy(
                        servers = sampleSession().servers.map { it.copy(role = "member") }
                    )
                ),
                FakeSettingsNetworkClient()
            ).getBilling()

        assertTrue(billing is DataResult.Content)
        val billingSnapshot = billing.data
        assertEquals("Team", billingSnapshot.currentPlan.planName)
        assertEquals(3, billingSnapshot.currentPlan.usageRows.size)
        assertTrue(billingSnapshot.plans.any { it.current })
        assertTrue(billingSnapshot.plans.any { it.comingSoon })
        assertTrue(denied is DataResult.NoPermission)
        assertEquals("billing_owner_required", denied.blocker.code)
    }

    @Test
    fun settingsRepositoryMapsAuthPermissionAndServerErrors() = runBlocking {
        val noSession = RealSettingsRepository(InMemorySlockAuthSessionStore(null), FakeSettingsNetworkClient()).getAccount()
        val forbidden = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(responseCode = 403)).getAccount()
        val unavailable = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(responseCode = 503)).getAccount()
        val parse = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(accountJson = """{"user":""")).getAccount()

        assertTrue(noSession is DataResult.NoPermission)
        assertEquals("auth_required", noSession.blocker.code)
        assertTrue(forbidden is DataResult.NoPermission)
        assertEquals("server_forbidden", forbidden.blocker.code)
        assertTrue(unavailable is DataResult.Error)
        assertEquals("server_unavailable", unavailable.error.code)
        assertTrue(parse is DataResult.Error)
        assertEquals("network_parse_failed", parse.error.code)
    }

    @Test
    fun serverSettingsMapsWebConfirmedSectionsWithoutGuessingJoinLinks() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    membersJson = """
                        [
                          {"userId":"user-1","displayName":"Owner","email":"owner@slock.ai","role":"owner"},
                          {"userId":"admin-1","displayName":"Admin User","email":"admin@slock.ai","role":"admin"}
                        ]
                    """.trimIndent(),
                    usageJson = """{"machines":1,"agents":10,"channels":12}""",
                    billingSubscriptionJson = """{"subscription":{"status":"active","currentPeriodEnd":"2026-06-01T00:00:00Z"},"stripeConfigured":true}""",
                    onboardingJson = """{"setupModalReminderOptOut":true}"""
                )
            )

        val server = repository.getServerSettings()

        assertTrue(server is DataResult.Content)
        val serverSnapshot = server.data
        assertEquals("Slock Android", serverSnapshot.profile.name)
        assertEquals("slock-android", serverSnapshot.profile.slug)
        assertEquals(true, serverSnapshot.profile.canSave)
        assertEquals(
            listOf("admins", "billing", "pending_invites", "join_links", "onboarding_agent", "danger"),
            serverSnapshot.sections.map { it.key }
        )
        assertEquals("Admin User", serverSnapshot.admins.single().name)
        assertEquals("Admin", serverSnapshot.admins.single().role)
        assertTrue(serverSnapshot.usageRows.any { it.label == "Billing Status" && it.value == "Active" })
        assertTrue(serverSnapshot.usageRows.any { it.label == "Computers" && it.value == "1" })
        assertEquals(true, serverSnapshot.onboarding?.setupModalReminderOptOut)
        assertEquals(SettingsSectionAvailability.Empty, serverSnapshot.sections.first { it.key == "join_links" }.state)
        assertTrue(serverSnapshot.sections.all { it.state != SettingsSectionAvailability.Unavailable })
    }

    @Test
    fun computersAndMobileKeepExistingSettingsContracts() = runBlocking {
        val repository = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(responseCode = 404))

        val computers = repository.getComputers()
        val mobile = repository.getMobileSettings()

        assertTrue(computers is DataResult.Error)
        assertEquals("settings_machines_contract_mismatch", computers.error.code)
        assertEquals(false, computers.error.retryable)
        assertTrue(mobile is DataResult.Content)
        assertEquals(true, mobile.data.pushNotifications.available)
        assertEquals(false, mobile.data.muteServer.available)
        assertEquals(true, mobile.data.sendTestEnabled)
    }

    @Test
    fun computersUseSelectedServerMachinesEndpointAndMapCanonicalFields() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeSettingsNetworkClient(
                machinesJson = """
                    {
                      "machines": [
                        {"id":"machine-1","name":"Jiachengs-MacBook-Pro","hostname":"Jiachengs-MacBook-Pro","daemonVersion":"1.4.2","status":"online"},
                        {"id":"machine-2","name":"Build host","daemonVersion":"1.3.0","status":"offline"}
                      ],
                      "latestDaemonVersion": "1.5.0"
                    }
                """.trimIndent()
            )
        val repository = RealSettingsRepository(sampleStore(), network, logger = logs::add, warningLogger = logs::add)

        val result = repository.getComputers()

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals("server-1", network.machinesServerId)
        assertEquals(false, snapshot.canAddComputer)
        assertEquals("1.5.0", snapshot.latestDaemonVersion)
        assertEquals(2, snapshot.computers.size)
        assertEquals("Jiachengs-MacBook-Pro", snapshot.computers[0].name)
        assertEquals("1.4.2", snapshot.computers[0].daemonVersion)
        assertEquals(true, snapshot.computers[0].online)
        assertEquals("machine-2", snapshot.computers[1].id)
        assertEquals(false, snapshot.computers[1].online)
        assertTrue(logs.any { it == "machines_fetch_start serverId=server-1" })
        assertTrue(logs.any { it == "machines_fetch_content serverId=server-1 count=2 canAdd=false" })
        assertTrue(logs.none { it.contains("Bearer") || it.contains("token", ignoreCase = true) })
    }

    @Test
    fun computersRejectGuessedNestedWrappersAndHandleMissingServer() = runBlocking {
        val nestedWrapperResult =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    machinesJson = """{"data":{"nodes":[{"id":"machine-1","name":"Desktop","is_online":true}]}}"""
                )
            ).getComputers()
        val missingServer =
            RealSettingsRepository(
                sampleStore(sampleSession().copy(selectedServerId = null, servers = emptyList())),
                FakeSettingsNetworkClient()
            ).getComputers()

        assertTrue(nestedWrapperResult is DataResult.Error)
        assertEquals("settings_machines_contract_mismatch", nestedWrapperResult.error.code)
        assertTrue(missingServer is DataResult.NoPermission)
        assertEquals("server_required", missingServer.blocker.code)
    }

    @Test
    fun serverSettingsRejectsGuessedBillingPlanWrapper() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(billingSubscriptionJson = """{"subscription":{"plan":"hobby"}}""")
            )

        val server = repository.getServerSettings()

        assertTrue(server is DataResult.Content)
        val billing = server.data.sections.first { it.key == "billing" }
        assertTrue(billing.detail.contains("settings_billing_subscription_contract_mismatch"))
    }

    @Test
    fun serverInviteRejectsGenericOkWrapperAndAcceptsCanonicalInvite() = runBlocking {
        val rejected =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(inviteJson = """{"ok":true}""")
            ).createServerInvite(SettingsServerInviteCreateRequest("new@slock.ai"))
        val network = FakeSettingsNetworkClient(inviteJson = """{"id":"invite-1","email":"new@slock.ai"}""")
        val accepted =
            RealSettingsRepository(sampleStore(), network)
                .createServerInvite(SettingsServerInviteCreateRequest("new@slock.ai"))

        assertTrue(rejected is DataResult.Error)
        assertEquals("settings_server_invite_contract_mismatch", rejected.error.code)
        assertTrue(accepted is DataResult.Content)
        assertEquals(true, accepted.data.success)
        assertEquals("""{"email":"new@slock.ai"}""", network.inviteBodies.single())
    }

    @Test
    fun computerDetailCombinesMachineWorkspacesAndAgents() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                machinesJson = """
                    {"machines":[{"id":"machine-1","name":"MacBook","hostname":"mac.local","os":"macOS","daemonVersion":"1.4.2","status":"online","lastHeartbeat":"2026-05-07T01:00:00Z"}]}
                """.trimIndent(),
                membersJson = """
                    [
                      {"userId":"agent-1","name":"settings-integrator","displayName":"Settings Integrator","description":"Settings real-data wiring","avatarUrl":"pixel:diamond","role":"member"}
                    ]
                """.trimIndent(),
                workspacesJson = """
                    [{"directoryName":"Slock","status":"active","lastModified":"2026-05-07T01:01:00Z"}]
                """.trimIndent(),
                agentsJson = """
                    [
                      {"id":"agent-1","name":"settings-integrator","displayName":"Settings Integrator","machineId":"machine-1","status":"active","creatorId":"Android"},
                      {"id":"agent-2","name":"Other Agent","machineId":"machine-2","status":"active"}
                    ]
                """.trimIndent()
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val result = repository.getComputerDetail("machine-1")

        assertTrue(result is DataResult.Content)
        val detail = result.data
        assertEquals("machine-1", network.workspacesMachineId)
        assertEquals("MacBook", detail.computer.name)
        assertEquals("macOS", detail.computer.platform)
        assertEquals(1, detail.workspaces.size)
        assertEquals("Slock", detail.workspaces.first().name)
        assertEquals(1, detail.agents.size)
        assertEquals("Settings Integrator", detail.agents.first().name)
        assertEquals("Settings real-data wiring", detail.agents.first().memberProfile?.description)
        assertEquals("settings-integrator", detail.agents.first().memberProfile?.handle)
        assertTrue(detail.sections.any { it.key == "machine_status_events" })
    }

    @Test
    fun computerDetailKeepsMachineVisibleWhenWorkspaceOrAgentSectionsFail() = runBlocking {
        val logs = mutableListOf<String>()
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    machinesJson = """{"machines":[{"id":"machine-1","name":"MacBook","daemonVersion":"1.4.2","status":"online"}]}""",
                    workspacesResponseCode = 503,
                    agentsResponseCode = 403
                ),
                logger = logs::add,
                warningLogger = logs::add
            )

        val result = repository.getComputerDetail("machine-1")

        assertTrue(result is DataResult.Content)
        val detail = result.data
        assertEquals("MacBook", detail.computer.name)
        assertTrue(detail.workspaces.isEmpty())
        assertEquals("server_unavailable", detail.workspacesError?.code)
        assertEquals(true, detail.workspacesError?.retryable)
        assertEquals("server_forbidden", detail.agentsError?.code)
        assertEquals(false, detail.agentsError?.retryable)
        assertTrue(logs.any { it.contains("section=workspaces result=error code=server_unavailable") })
        assertTrue(logs.any { it.contains("section=agents result=noPermission code=server_forbidden") })
        assertTrue(logs.none { it.contains("Bearer") || it.contains("token", ignoreCase = true) })
    }

    @Test
    fun computerActionsAndRuntimeModelsUseTypedMachineEndpoints() = runBlocking {
        val network = FakeSettingsNetworkClient()
        val repository = RealSettingsRepository(sampleStore(), network)

        val register = repository.registerComputer(SettingsComputerRegisterRequest("Builder Mac"))
        val rename = repository.renameComputer(SettingsComputerRenameRequest("machine-1", "Builder Mac 2"))
        val rotate = repository.rotateComputerKey("machine-1")
        val runtimeModels = repository.getComputerRuntimeModels("machine-1", "claude")
        val deleteWorkspace = repository.deleteComputerWorkspace(SettingsComputerWorkspaceDeleteRequest("machine-1", "agent-old"))
        val deleteComputer = repository.deleteComputer("machine-1")

        assertTrue(register is DataResult.Content)
        assertEquals("machine-9", register.data.machine.id)
        assertEquals("sk_machine_ab", register.data.apiKeyPrefix)
        assertTrue(rename is DataResult.Content)
        assertEquals("Builder Mac 2", rename.data.name)
        assertTrue(rotate is DataResult.Content)
        assertEquals("sk_machine_ro", rotate.data.apiKeyPrefix)
        assertTrue(runtimeModels is DataResult.Content)
        assertEquals(listOf("sonnet", "opus"), runtimeModels.data.models)
        assertEquals("sonnet", runtimeModels.data.defaultModel)
        assertTrue(deleteWorkspace is DataResult.Content)
        assertEquals("delete_workspace", deleteWorkspace.data.action)
        assertTrue(deleteComputer is DataResult.Content)
        assertEquals("delete", deleteComputer.data.action)
        assertEquals("""{"name":"Builder Mac"}""", network.registerBodies.single())
        assertEquals("""{"name":"Builder Mac 2"}""", network.renameBodies.single())
        assertEquals("server-1", network.runtimeModelsServerId)
        assertEquals("machine-1", network.runtimeModelsMachineId)
        assertEquals("claude", network.runtimeModelsRuntime)
        assertEquals("agent-old", network.deletedWorkspaceDirectoryName)
        assertEquals("machine-1", network.deletedMachineId)
    }

    private class FakeSettingsNetworkClient(
        private val accountJson: String = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User"}""",
        private val membersJson: String = """[]""",
        private val usageJson: String = """{"machines":0,"agents":0,"channels":0}""",
        private val billingSubscriptionJson: String = """{"subscription":null,"stripeConfigured":false}""",
        private val onboardingJson: String = """{"setupModalReminderOptOut":false}""",
        private val inviteJson: String = """{"id":"invite-1","email":"new@slock.ai"}""",
        private val machinesJson: String = """{"machines":[]}""",
        private val workspacesJson: String = """[]""",
        private val agentsJson: String = """[]""",
        private val registerMachineJson: String =
            """{"machine":{"id":"machine-9","name":"Builder Mac","status":"offline","daemonVersion":null,"hostname":null,"os":null},"apiKey":"sk_machine_abc123"}""",
        private val renameMachineJson: String =
            """{"id":"machine-1","name":"Builder Mac 2","status":"online","daemonVersion":"1.5.0","hostname":"builder.local","os":"macOS"}""",
        private val rotateMachineKeyJson: String = """{"apiKey":"sk_machine_rot987"}""",
        private val runtimeModelsJson: String = """{"models":["sonnet","opus"],"default":"sonnet"}""",
        private val commandJson: String = """{"ok":true}""",
        private val machinesResponseCode: Int? = null,
        private val workspacesResponseCode: Int? = null,
        private val agentsResponseCode: Int? = null,
        private val responseCode: Int = 200
    ) : SlockNetworkClient {
        var machinesServerId: String? = null
            private set
        var workspacesMachineId: String? = null
            private set
        var runtimeModelsServerId: String? = null
            private set
        var runtimeModelsMachineId: String? = null
            private set
        var runtimeModelsRuntime: String? = null
            private set
        var deletedMachineId: String? = null
            private set
        var deletedWorkspaceDirectoryName: String? = null
            private set
        val accountUpdateBodies = mutableListOf<String>()
        val inviteBodies = mutableListOf<String>()
        val registerBodies = mutableListOf<String>()
        val renameBodies = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ai.slock.compose.model.ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            return when {
                normalized == "auth/me" && method == "GET" ->
                    response(accountJson)
                normalized == "auth/me" && method == "PATCH" -> {
                    accountUpdateBodies += bodyText.orEmpty()
                    response(accountJson)
                }
                normalized.matches(Regex("""servers/[^/]+/machines""")) && method == "GET" -> {
                    machinesServerId = normalized.split("/")[1]
                    response(machinesJson, machinesResponseCode ?: responseCode)
                }
                normalized.matches(Regex("""servers/[^/]+/machines""")) && method == "POST" -> {
                    registerBodies += bodyText.orEmpty()
                    response(registerMachineJson)
                }
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+$""")) && method == "PATCH" -> {
                    renameBodies += bodyText.orEmpty()
                    response(renameMachineJson)
                }
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+$""")) && method == "DELETE" -> {
                    deletedMachineId = normalized.split("/")[3]
                    response(commandJson)
                }
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+/rotate-key""")) && method == "POST" ->
                    response(rotateMachineKeyJson)
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+/workspaces""")) && method == "GET" -> {
                    val parts = normalized.split("/")
                    workspacesMachineId = parts[3]
                    response(workspacesJson, workspacesResponseCode ?: responseCode)
                }
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+/workspaces/[^/]+""")) && method == "DELETE" -> {
                    val parts = normalized.split("/")
                    deletedWorkspaceDirectoryName = parts[5]
                    response(commandJson)
                }
                normalized.matches(Regex("""servers/[^/]+/machines/[^/]+/runtime-models/[^/]+""")) && method == "GET" -> {
                    val parts = normalized.split("/")
                    runtimeModelsServerId = parts[1]
                    runtimeModelsMachineId = parts[3]
                    runtimeModelsRuntime = parts[5]
                    response(runtimeModelsJson)
                }
                normalized == "agents" && method == "GET" ->
                    response(agentsJson, agentsResponseCode ?: responseCode)
                normalized.matches(Regex("""servers/[^/]+/members""")) && method == "GET" ->
                    response(membersJson)
                normalized.matches(Regex("""servers/[^/]+/usage""")) && method == "GET" ->
                    response(usageJson)
                normalized == "billing/subscription" && method == "GET" ->
                    response(billingSubscriptionJson)
                normalized.matches(Regex("""servers/[^/]+/onboarding-settings""")) && method == "GET" ->
                    response(onboardingJson)
                normalized.matches(Regex("""servers/[^/]+""")) && method == "PATCH" -> {
                    val serverId = normalized.split("/")[1]
                    response("""{"id":"$serverId","name":"Slock Android"}""")
                }
                normalized.matches(Regex("""servers/[^/]+/invites""")) && method == "POST" -> {
                    inviteBodies += bodyText.orEmpty()
                    response(inviteJson)
                }
                normalized.matches(Regex("""servers/[^/]+""")) && method == "DELETE" ->
                    response("""{"ok":true}""")
                else -> error("Unhandled request $method $normalized")
            }
        }

        private fun response(json: String, code: Int = responseCode): HomeNetworkResponse =
            if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = json)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = """{"error":"failed"}""")
            }
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
