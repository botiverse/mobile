package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.ConnectedAccountStatus
import ai.slock.compose.data.DataResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.compose.network.SlockRequestPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.slock.android.core.data.network.ApiResponse

class RealSettingsRepositoryTest {
    @Test
    fun accountMapsProfileAndConnectedAccounts() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                accountJson = """
                    {
                      "user": {
                        "id": "user-1",
                        "email": "user@slock.ai",
                        "displayName": "Slock User",
                        "handle": "@slock-user",
                        "emailVerified": true
                      },
                      "connectedAccounts": [
                        {"provider":"Google","identifier":"user@slock.ai","status":"connected"},
                        {"provider":"GitHub","identifier":"github.com/slock","status":"not_connected"}
                      ],
                      "canEditProfile": true
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getAccount() }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("Slock User", snapshot.profile.displayName)
        assertEquals("@slock-user", snapshot.profile.handle)
        assertEquals(true, snapshot.profile.verified)
        assertEquals("Owner", snapshot.profile.roleLabel)
        assertEquals(ConnectedAccountStatus.NotConnected, snapshot.connectedAccounts[1].status)
    }

    @Test
    fun sessionsMapRowsAndCurrentSessionCannotBeRevoked() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                sessionsJson = """
                    {
                      "sessions": [
                        {"id":"current","deviceName":"Android","browserLabel":"Slock Android","locationLabel":"Current device","lastActiveLabel":"Active now","current":true},
                        {"id":"web","device":"MacBook","browser":"Chrome","location":"Shanghai","lastActiveAt":"2026-05-06T12:00:00Z"}
                      ],
                      "canRevokeOtherSessions": true
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getSessions() }

        assertTrue(result is DataResult.Content)
        val sessions = (result as DataResult.Content).data.sessions
        assertEquals(2, sessions.size)
        assertEquals(false, sessions[0].revokeEnabled)
        assertEquals(true, sessions[1].revokeEnabled)
        assertEquals("MacBook", sessions[1].deviceName)
    }

    @Test
    fun updateProfileAndRevokeSessionReturnActionResults() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                accountJson = """{"user":{"id":"user-1","email":"new@slock.ai","displayName":"New Name","handle":"@new","emailVerified":true}}""",
                revokeJson = """{"sessionId":"web","revoked":true}"""
            )
        )

        val account = runSuspend {
            repository.updateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai"))
        }
        val revoked = runSuspend { repository.revokeSession("web") }

        assertTrue(account is DataResult.Content)
        assertEquals("New Name", (account as DataResult.Content).data.profile.displayName)
        assertTrue(revoked is DataResult.Content)
        val revokedResult = (revoked as DataResult.Content).data
        assertEquals("web", revokedResult.sessionId)
        assertEquals(true, revokedResult.revoked)
    }

    @Test
    fun billingMapsReadablePlanStateAndRequiresOwnerPermission() {
        val billing = runSuspend {
            RealSettingsRepository(
                store = InMemoryAuthSessionStore(sampleSession),
                api = FakeSlockApi()
            ).getBilling()
        }
        val denied = runSuspend {
            RealSettingsRepository(
                store = InMemoryAuthSessionStore(
                    sampleSession.copy(
                        servers = sampleSession.servers.map { it.copy(role = "member") }
                    )
                ),
                api = FakeSlockApi()
            ).getBilling()
        }

        assertTrue(billing is DataResult.Content)
        val billingSnapshot = (billing as DataResult.Content).data
        assertEquals("Team", billingSnapshot.currentPlan.planName)
        assertEquals(3, billingSnapshot.currentPlan.usageRows.size)
        assertTrue(billingSnapshot.plans.any { it.current })
        assertTrue(billingSnapshot.plans.any { it.comingSoon })

        assertTrue(denied is DataResult.NoPermission)
        assertEquals("billing_owner_required", (denied as DataResult.NoPermission).blocker.code)
    }

    @Test
    fun settingsRepositoryMapsAuthPermissionAndServerErrors() {
        val noSession = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(null), FakeSlockApi()).getAccount()
        }
        val forbidden = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(sampleSession), FakeSlockApi(responseCode = 403)).getSessions()
        }
        val unavailable = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(sampleSession), FakeSlockApi(responseCode = 503)).getAccount()
        }
        val parse = runSuspend {
            RealSettingsRepository(
                InMemoryAuthSessionStore(sampleSession),
                FakeSlockApi(accountJson = """{"user":""")
            ).getAccount()
        }

        assertTrue(noSession is DataResult.NoPermission)
        assertEquals("auth_required", (noSession as DataResult.NoPermission).blocker.code)
        assertTrue(forbidden is DataResult.NoPermission)
        assertEquals("server_forbidden", (forbidden as DataResult.NoPermission).blocker.code)
        assertTrue(unavailable is DataResult.Error)
        assertEquals("server_unavailable", (unavailable as DataResult.Error).error.code)
        assertTrue(parse is DataResult.Error)
        assertEquals("network_parse_failed", (parse as DataResult.Error).error.code)
    }

    @Test
    fun sessionsAndRevokeMapMissingEndpointsToContractMismatch() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(responseCode = 404)
        )

        val sessions = runSuspend { repository.getSessions() }
        val revoke = runSuspend { repository.revokeSession("web") }

        assertTrue(sessions is DataResult.Error)
        assertEquals("settings_sessions_contract_mismatch", (sessions as DataResult.Error).error.code)
        assertEquals(false, sessions.error.retryable)
        assertTrue(revoke is DataResult.Error)
        assertEquals("settings_session_revoke_contract_mismatch", (revoke as DataResult.Error).error.code)
        assertEquals(false, revoke.error.retryable)
    }

    @Test
    fun serverSettingsMapsWebConfirmedSectionsWithoutGuessingJoinLinks() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                membersJson = """
                    {"members":[
                      {"userId":"user-1","displayName":"Owner","email":"owner@slock.ai","role":"owner"},
                      {"userId":"admin-1","displayName":"Admin User","email":"admin@slock.ai","role":"admin"}
                    ]}
                """.trimIndent(),
                usageJson = """{"computers":1,"computersLimit":"Unlimited","agents":10,"agentsLimit":"Unlimited","channels":12,"channelsLimit":"Unlimited"}""",
                billingSubscriptionJson = """{"subscription":{"plan":"hobby"}}""",
                onboardingJson = """{"setupModalReminderOptOut":true}"""
            )
        )

        val server = runSuspend { repository.getServerSettings() }

        assertTrue(server is DataResult.Content)
        val serverSnapshot = (server as DataResult.Content).data
        assertEquals("Slock Android", serverSnapshot.profile.name)
        assertEquals("slock-android", serverSnapshot.profile.slug)
        assertEquals(true, serverSnapshot.profile.canSave)
        assertEquals(
            listOf("admins", "billing", "pending_invites", "join_links", "onboarding_agent", "danger"),
            serverSnapshot.sections.map { it.key }
        )
        assertEquals("Admin User", serverSnapshot.admins.single().name)
        assertEquals("Admin", serverSnapshot.admins.single().role)
        assertTrue(serverSnapshot.usageRows.any { it.label == "Plan" && it.value == "Hobby" })
        assertTrue(serverSnapshot.usageRows.any { it.label == "Computers" && it.value == "1 / Unlimited" })
        assertEquals(true, serverSnapshot.onboarding?.setupModalReminderOptOut)
        assertEquals(SettingsSectionAvailability.Empty, serverSnapshot.sections.first { it.key == "join_links" }.state)
        assertTrue(serverSnapshot.sections.all { it.state != SettingsSectionAvailability.Unavailable })
    }

    @Test
    fun computersAndMobileKeepExistingSettingsContracts() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(responseCode = 404)
        )

        val computers = runSuspend { repository.getComputers() }
        val mobile = runSuspend { repository.getMobileSettings() }

        assertTrue(computers is DataResult.Error)
        assertEquals("settings_machines_contract_mismatch", (computers as DataResult.Error).error.code)
        assertEquals(false, computers.error.retryable)

        assertTrue(mobile is DataResult.Content)
        val mobileSnapshot = (mobile as DataResult.Content).data
        assertEquals(true, mobileSnapshot.pushNotifications.available)
        assertEquals(false, mobileSnapshot.muteServer.available)
        assertEquals(true, mobileSnapshot.sendTestEnabled)
    }

    @Test
    fun computersUseSelectedServerMachinesEndpointAndMapWrappers() {
        val logs = mutableListOf<String>()
        val api = FakeSlockApi(
            machinesJson = """
                {
                  "machines": [
                    {"id":"machine-1","hostname":"Jiachengs-MacBook-Pro","daemonVersion":"1.4.2","status":"online"},
                    {"machineId":"machine-2","displayName":"Build host","version":"1.3.0","online":false}
                  ],
                  "latestDaemonVersion": "1.5.0",
                  "canAddComputer": true
                }
            """.trimIndent()
        )
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            logger = logs::add,
            warningLogger = logs::add
        )

        val result = runSuspend { repository.getComputers() }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("server-1", api.machinesServerId)
        assertEquals(true, snapshot.canAddComputer)
        assertEquals("1.5.0", snapshot.latestDaemonVersion)
        assertEquals(2, snapshot.computers.size)
        assertEquals("Jiachengs-MacBook-Pro", snapshot.computers[0].name)
        assertEquals("1.4.2", snapshot.computers[0].daemonVersion)
        assertEquals(true, snapshot.computers[0].online)
        assertEquals("machine-2", snapshot.computers[1].id)
        assertEquals(false, snapshot.computers[1].online)
        assertTrue(logs.any { it == "machines_fetch_start serverId=server-1" })
        assertTrue(logs.any { it == "machines_fetch_content serverId=server-1 count=2 canAdd=true" })
        assertTrue(logs.none { it.contains("Bearer") || it.contains("token", ignoreCase = true) })
    }

    @Test
    fun computersMapArrayNestedNodesAndMissingServer() {
        val arrayResult = runSuspend {
            RealSettingsRepository(
                store = InMemoryAuthSessionStore(sampleSession),
                api = FakeSlockApi(
                    machinesJson = """{"data":{"nodes":[{"id":"machine-1","name":"Desktop","is_online":true}]}}"""
                )
            ).getComputers()
        }
        val missingServer = runSuspend {
            RealSettingsRepository(
                store = InMemoryAuthSessionStore(sampleSession.copy(selectedServerId = null, servers = emptyList())),
                api = FakeSlockApi()
            ).getComputers()
        }

        assertTrue(arrayResult is DataResult.Content)
        val computers = (arrayResult as DataResult.Content).data.computers
        assertEquals(1, computers.size)
        assertEquals("Desktop", computers.first().name)
        assertEquals(true, computers.first().online)
        assertTrue(missingServer is DataResult.NoPermission)
        assertEquals("server_required", (missingServer as DataResult.NoPermission).blocker.code)
    }

    @Test
    fun computerDetailCombinesMachineWorkspacesAndAgents() {
        val api = FakeSlockApi(
            machinesJson = """
                {"machines":[{"id":"machine-1","name":"MacBook","hostname":"mac.local","os":"macOS","daemonVersion":"1.4.2","status":"online","lastSeenAt":"2026-05-07T01:00:00Z"}]}
            """.trimIndent(),
            membersJson = """
                {"members":[
                  {"id":"agent-1","displayName":"Settings Integrator","handle":"@settings-integrator","description":"Settings real-data wiring","avatar":"pixel:diamond","status":"online"}
                ]}
            """.trimIndent(),
            workspacesJson = """
                {"workspaces":[{"directoryName":"Slock","path":"~/AndroidStudioProjects/Slock","status":"active","lastActivityAt":"2026-05-07T01:01:00Z"}]}
            """.trimIndent(),
            agentsJson = """
                {"agents":[
                  {"id":"agent-1","name":"Settings Integrator","machineId":"machine-1","status":"active","ownerName":"Android"},
                  {"id":"agent-2","name":"Other Agent","machineId":"machine-2","status":"active"}
                ]}
            """.trimIndent()
        )
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api
        )

        val result = runSuspend { repository.getComputerDetail("machine-1") }

        assertTrue(result is DataResult.Content)
        val detail = (result as DataResult.Content).data
        assertEquals("machine-1", api.workspacesMachineId)
        assertEquals("MacBook", detail.computer.name)
        assertEquals("macOS", detail.computer.platform)
        assertEquals(1, detail.workspaces.size)
        assertEquals("Slock", detail.workspaces.first().name)
        assertEquals(1, detail.agents.size)
        assertEquals("Settings Integrator", detail.agents.first().name)
        assertEquals("Settings real-data wiring", detail.agents.first().memberProfile?.description)
        assertEquals("@settings-integrator", detail.agents.first().memberProfile?.handle)
        assertTrue(detail.sections.any { it.key == "machine_status_events" })
    }

    @Test
    fun computerDetailKeepsMachineVisibleWhenWorkspaceOrAgentSectionsFail() {
        val logs = mutableListOf<String>()
        val api = FakeSlockApi(
            machinesJson = """{"machines":[{"id":"machine-1","name":"MacBook","daemonVersion":"1.4.2","status":"online"}]}""",
            workspacesResponseCode = 503,
            agentsResponseCode = 403
        )
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            logger = logs::add,
            warningLogger = logs::add
        )

        val result = runSuspend { repository.getComputerDetail("machine-1") }

        assertTrue(result is DataResult.Content)
        val detail = (result as DataResult.Content).data
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

    private class FakeSlockApi(
        private val accountJson: String = """{"user":{"id":"user-1","email":"user@slock.ai","displayName":"Slock User"}}""",
        private val sessionsJson: String = """{"sessions":[]}""",
        private val revokeJson: String = """{"ok":true}""",
        private val membersJson: String = """{"members":[]}""",
        private val usageJson: String = """{"usage":{}}""",
        private val billingSubscriptionJson: String = """{"subscription":{}}""",
        private val onboardingJson: String = """{"settings":{}}""",
        private val machinesJson: String = """{"machines":[]}""",
        private val workspacesJson: String = """{"workspaces":[]}""",
        private val agentsJson: String = """{"agents":[]}""",
        private val machinesResponseCode: Int? = null,
        private val workspacesResponseCode: Int? = null,
        private val agentsResponseCode: Int? = null,
        private val responseCode: Int = 200
    ) : SlockApi {
        var machinesServerId: String? = null
            private set
        var workspacesMachineId: String? = null
            private set

        override suspend fun settingsAccount(priority: SlockRequestPriority): ApiResponse = response(accountJson)

        override suspend fun updateSettingsAccount(priority: SlockRequestPriority, body: RequestBody): ApiResponse = response(accountJson)

        override suspend fun settingsSessions(priority: SlockRequestPriority): ApiResponse = response(sessionsJson)

        override suspend fun revokeSettingsSession(priority: SlockRequestPriority, sessionId: String): ApiResponse = response(revokeJson)

        override suspend fun settingsMachines(priority: SlockRequestPriority, serverId: String): ApiResponse {
            machinesServerId = serverId
            return response(machinesJson, machinesResponseCode ?: responseCode)
        }

        override suspend fun settingsMachineWorkspaces(priority: SlockRequestPriority,
            serverId: String,
            machineId: String
        ): ApiResponse {
            workspacesMachineId = machineId
            return response(workspacesJson, workspacesResponseCode ?: responseCode)
        }

        override suspend fun settingsAgents(priority: SlockRequestPriority): ApiResponse =
            response(agentsJson, agentsResponseCode ?: responseCode)

        override suspend fun updateAgent(priority: SlockRequestPriority,
            agentId: String,
            body: RequestBody
        ): ApiResponse = response("""{"ok":true}""")

        override suspend fun channels(priority: SlockRequestPriority, archived: String?): ApiResponse = response("""{"channels":[]}""")

        override suspend fun createChannel(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"id":"channel-1","name":"android-dev"}""")

        override suspend fun joinChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun leaveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun archiveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun deleteChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun serverMembers(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response(membersJson)

        override suspend fun updateSettingsServerProfile(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"id":"$serverId","name":"Slock Android"}""")

        override suspend fun settingsServerUsage(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response(usageJson)

        override suspend fun settingsBillingSubscription(priority: SlockRequestPriority): ApiResponse =
            response(billingSubscriptionJson)

        override suspend fun createSettingsServerInvite(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"invite":{}}""")

        override suspend fun settingsServerOnboardingSettings(priority: SlockRequestPriority,
            serverId: String
        ): ApiResponse =
            response(onboardingJson)

        override suspend fun updateSettingsServerOnboardingSettings(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"settings":{}}""")

        override suspend fun deleteSettingsServer(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun memberProfile(priority: SlockRequestPriority, serverId: String, memberId: String): ApiResponse =
            response("""{"profile":{"id":"$memberId","displayName":"Member","presence":"offline"}}""")

        override suspend fun agentWorkspaceFiles(priority: SlockRequestPriority, agentId: String, dirPath: String?): ApiResponse =
            response("""{"files":[]}""")

        override suspend fun readAgentWorkspaceFile(priority: SlockRequestPriority, agentId: String, path: String): ApiResponse =
            response("""{"file":{"path":"$path","content":""}}""")

        override suspend fun agentDirectMessages(priority: SlockRequestPriority, agentId: String): ApiResponse =
            response("""{"directMessages":[]}""")

        override suspend fun reminders(priority: SlockRequestPriority, ownerAgentId: String, status: String): ApiResponse =
            response("""{"reminders":[]}""")

        override suspend fun agentActivityLog(priority: SlockRequestPriority, agentId: String, limit: Int): ApiResponse =
            response("""{"trajectoryLogs":[]}""")

        override suspend fun channelMembers(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"members":[]}""")

        override suspend fun addChannelMember(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun removeChannelMember(priority: SlockRequestPriority,
            channelId: String,
            kind: String,
            memberId: String
        ): ApiResponse =
            response("""{"ok":true,"removed":{"id":"$memberId","kind":"$kind"}}""")

        override suspend fun directMessages(priority: SlockRequestPriority): ApiResponse = response("""[]""")

        override suspend fun openDirectMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"channel":{"id":"dm-1","name":"Direct message","type":"dm"}}""")

        override suspend fun channelUnreadCounts(priority: SlockRequestPriority): ApiResponse = response("""{}""")

        override suspend fun markChannelReadToSeq(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun markChannelReadAll(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun markChannelUnread(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun inbox(priority: SlockRequestPriority, filter: String, limit: Int, offset: Int): ApiResponse =
            response("""{"items":[]}""")

        override suspend fun markInboxReadAll(priority: SlockRequestPriority): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun serverTasks(priority: SlockRequestPriority): ApiResponse =
            response("""{"tasks":[]}""")

        override suspend fun channelTasks(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response("""{"tasks":[]}""")

        override suspend fun claimTask(priority: SlockRequestPriority, taskId: String): ApiResponse =
            response("""{"task":{}}""")

        override suspend fun updateTaskStatus(priority: SlockRequestPriority,
            taskId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"task":{}}""")

        override suspend fun saved(priority: SlockRequestPriority, limit: Int, offset: Int): ApiResponse = response("""{"items":[]}""")

        override suspend fun saveMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun unsaveMessage(priority: SlockRequestPriority, messageId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun searchMessages(
            priority: SlockRequestPriority,
            query: String,
            limit: Int,
            offset: Int,
            sort: String?,
            senderId: String?,
            channelId: String?,
            after: String?,
            before: String?
        ): ApiResponse =
            response("""{"results":[]}""")

        override suspend fun channelMessages(priority: SlockRequestPriority,
            channelId: String,
            limit: Int,
            beforeSeq: Long?
        ): ApiResponse = response("""{"messages":[]}""")

        override suspend fun messageContext(priority: SlockRequestPriority, messageId: String): ApiResponse =
            response("""{"messages":[]}""")

        override suspend fun messageSync(priority: SlockRequestPriority,
            sinceSeq: Long,
            limit: Int,
            channelId: String?
        ): ApiResponse = response("""{"messages":[]}""")

        override suspend fun sendMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"message":{}}""")

        override suspend fun uploadAttachments(priority: SlockRequestPriority,
            channelId: RequestBody,
            files: List<okhttp3.MultipartBody.Part>
        ): ApiResponse = response("""{"attachments":[]}""")

        override suspend fun attachmentUrl(priority: SlockRequestPriority, attachmentId: String): ApiResponse =
            response("""{"url":"https://example.invalid/attachments/$attachmentId"}""")

        override suspend fun channelFiles(priority: SlockRequestPriority, channelId: String, limit: Int, cursor: String?): ApiResponse =
            response("""{"files":[]}""")

        override suspend fun channelThreads(priority: SlockRequestPriority, channelId: String, limit: Int): ApiResponse =
            response("""{"threads":[]}""")

        override suspend fun createOrOpenThread(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            response("""{"threadChannelId":"thread-1","replyCount":1}""")

        override suspend fun followedThreads(priority: SlockRequestPriority, limit: Int): ApiResponse =
            response("""{"threads":[]}""")

        override suspend fun followThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun unfollowThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun markThreadDone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun markThreadUndone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun serverUnreadSummary(priority: SlockRequestPriority): ApiResponse = response("""{"unreadCount":0}""")

        private fun response(json: String, code: Int = responseCode): ApiResponse =
            if (code in 200..299) {
                ApiResponse.success(json.jsonBody())
            } else {
                ApiResponse.error(code, """{"error":"failed"}""".jsonBody())
            }
    }

    private class InMemoryAuthSessionStore(
        private var session: AuthSession?
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()

        private val sampleSession = AuthSession(
            user = AuthUser(
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
            servers = listOf(
                AuthServer(
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

        private fun String.jsonBody(): ResponseBody = toResponseBody(json)
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
