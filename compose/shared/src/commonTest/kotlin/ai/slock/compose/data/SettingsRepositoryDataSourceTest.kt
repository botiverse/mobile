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
    fun languageRegionCombinesAccountPreferencesAndServerTranslation() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    accountJson = """
                        {
                          "id":"user-1",
                          "email":"user@slock.ai",
                          "name":"slock-user",
                          "preferredLanguage":"en",
                          "preferredTimezone":"Asia/Shanghai",
                          "autoTranslationEnabled":false,
                          "preferredTimeFormat":"12h"
                        }
                    """.trimIndent(),
                    translationJson = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":true}"""
                )
            )

        val languageRegion = repository.getLanguageRegionSettings()

        assertTrue(languageRegion is DataResult.Content)
        assertEquals(true, languageRegion.data.serverTranslation.enabled)
        assertEquals(false, languageRegion.data.autoTranslation.enabled)
        assertEquals(true, languageRegion.data.autoTranslation.available)
        assertEquals("en", languageRegion.data.browserLanguage.selectedKey)
        assertEquals("Asia/Shanghai", languageRegion.data.timezone.selectedKey)
        assertEquals("12h", languageRegion.data.timeFormat.selectedKey)
    }

    @Test
    fun languageRegionPreferenceUpdatesUseAuthMePatchBodies() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                accountJson = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","preferredTimezone":"Asia/Tokyo","preferredTimeFormat":"24h"}""",
                translationJson = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":true}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val timezone =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = "Asia/Tokyo")
            )
        val timeFormat =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimeFormat = "24h")
            )
        val clearLanguage =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(clearPreferredLanguage = true)
            )

        assertTrue(timezone is DataResult.Content)
        assertTrue(timeFormat is DataResult.Content)
        assertTrue(clearLanguage is DataResult.Content)
        assertEquals(
            listOf(
                """{"preferredTimezone":"Asia/Tokyo"}""",
                """{"preferredTimeFormat":"24h"}""",
                """{"preferredLanguage":null}"""
            ),
            network.accountUpdateBodies
        )
    }

    @Test
    fun billingMapsReadablePlanStateAndRequiresOwnerPermission() = runBlocking {
        val billing =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    usageJson = """{"machines":1,"agents":10,"channels":12}""",
                    billingSubscriptionJson = """{"subscription":{"status":"active","currentPeriodEnd":"2026-06-01T00:00:00Z","cancelAtPeriodEnd":true},"stripeConfigured":true}"""
                )
            ).getBilling()
        val denied =
            RealSettingsRepository(
                sampleStore(
                    sampleSession().copy(
                        servers = sampleSession().servers.map { it.copy(role = "admin") }
                    )
                ),
                FakeSettingsNetworkClient()
            ).getBilling()

        assertTrue(billing is DataResult.Content)
        val billingSnapshot = billing.data
        assertEquals("Team", billingSnapshot.currentPlan.planName)
        assertEquals(4, billingSnapshot.currentPlan.usageRows.size)
        assertEquals("1 / 8", billingSnapshot.currentPlan.usageRows.first { it.label == "Computers" }.value)
        assertEquals("10 / 40", billingSnapshot.currentPlan.usageRows.first { it.label == "Agents" }.value)
        assertEquals("Unlimited", billingSnapshot.currentPlan.usageRows.first { it.label == "History" }.value)
        assertEquals(listOf("Hobby", "Team", "Business"), billingSnapshot.plans.map { it.name })
        assertTrue(billingSnapshot.plans.single { it.name == "Team" }.current)
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
    fun serverSettingsMapsWebConfirmedSectionsIncludingInvitesAndJoinLinks() = runBlocking {
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
                    onboardingJson = """{"setupModalReminderOptOut":true}""",
                    invitesJson = """[{"id":"invite-1","invitedEmail":"new@slock.ai","createdAt":"2026-05-09T00:00:00Z"}]""",
                    joinLinksJson = """[{"id":"join-1","token":"token-123456789","createdAt":"2026-05-09T00:00:00Z","expiresAt":"2026-06-01T00:00:00Z","maxUses":10,"useCount":2}]""",
                    notificationSettingsJson = """{"serverPushMuted":true}"""
                )
            )

        val server = repository.getServerSettings()

        assertTrue(server is DataResult.Content)
        val serverSnapshot = server.data
        assertEquals("Slock Android", serverSnapshot.profile.name)
        assertEquals("slock-android", serverSnapshot.profile.slug)
        assertEquals(true, serverSnapshot.profile.canSave)
        assertEquals(
            listOf("admins", "billing", "pending_invites", "join_links", "onboarding_agent", "translation", "sidebar_order", "danger"),
            serverSnapshot.sections.map { it.key }
        )
        assertEquals("Admin User", serverSnapshot.admins.single().name)
        assertEquals("Admin", serverSnapshot.admins.single().role)
        assertTrue(serverSnapshot.usageRows.any { it.label == "Billing Status" && it.value == "Active" })
        assertTrue(serverSnapshot.usageRows.any { it.label == "Computers" && it.value == "1" })
        assertEquals(true, serverSnapshot.onboarding?.setupModalReminderOptOut)
        assertEquals(false, serverSnapshot.translation?.translationEnabled)
        assertEquals("manual", serverSnapshot.sidebarOrder?.channelSortMode)
        assertEquals("new@slock.ai", serverSnapshot.pendingInvites.single().email)
        assertEquals("/join/token-123456789", serverSnapshot.joinLinks.single().url)
        assertEquals(SettingsSectionAvailability.Available, serverSnapshot.sections.first { it.key == "pending_invites" }.state)
        assertEquals(SettingsSectionAvailability.Available, serverSnapshot.sections.first { it.key == "join_links" }.state)
        assertTrue(serverSnapshot.sections.all { it.state != SettingsSectionAvailability.Unavailable })
    }

    @Test
    fun computersAndMobileKeepTypedContracts() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    machinesResponseCode = 404,
                    notificationSettingsJson = """{"serverPushMuted":false}"""
                )
            )

        val computers = repository.getComputers()
        val mobile = repository.getMobileSettings()

        assertTrue(computers is DataResult.Error)
        assertEquals("http_404", computers.error.code)
        assertEquals(false, computers.error.retryable)
        assertTrue(mobile is DataResult.Content)
        assertEquals(true, mobile.data.pushNotifications.available)
        assertEquals(true, mobile.data.muteServer.available)
        assertEquals("Not muted", mobile.data.muteServer.statusLabel)
        assertEquals(true, mobile.data.sendTestEnabled)
    }

    @Test
    fun mobileMuteInviteAndJoinLinkActionsUseConfirmedContracts() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                notificationSettingsJson = """{"serverPushMuted":false}""",
                inviteDeleteJson = """{"ok":true}""",
                createJoinLinkJson = """{"token":"created-token","link":{"id":"join-2","createdAt":"2026-05-09T00:00:00Z","expiresAt":"2026-06-01T00:00:00Z","maxUses":5,"useCount":0}}""",
                joinLinkDeleteJson = """{"ok":true}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val muted = repository.setServerMute(true)
        val revokedInvite = repository.revokeServerInvite("invite-1")
        val createdJoinLink = repository.createServerJoinLink(
            SettingsServerJoinLinkCreateRequest(
                expiresAt = "2026-06-01T00:00:00Z",
                maxUses = 5
            )
        )
        val revokedJoinLink = repository.revokeServerJoinLink("join-2")

        assertTrue(muted is DataResult.Content)
        assertEquals(true, muted.data.muteServer.enabled)
        assertEquals("""{"serverPushMuted":true}""", network.notificationSettingsBodies.single())
        assertTrue(revokedInvite is DataResult.Content)
        assertEquals("invite-1", network.deletedInviteId)
        assertTrue(createdJoinLink is DataResult.Content)
        assertEquals("/join/created-token", createdJoinLink.data.url)
        assertEquals("""{"expiresAt":"2026-06-01T00:00:00Z","maxUses":5}""", network.joinLinkBodies.single())
        assertTrue(revokedJoinLink is DataResult.Content)
        assertEquals("join-2", network.deletedJoinLinkId)
    }

    @Test
    fun serverManagementContractsUseConfirmedShapes() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                onboardingJson = """{"setupModalReminderOptOut":false}""",
                translationJson = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":true}""",
                sidebarOrderJson = """{"channelOrder":["channel-1"],"agentOrder":["agent-1"],"dmOrder":["dm-1"],"channelSortMode":"manual","dmSortMode":"recent","pinnedSortMode":"az","pinnedChannelIds":["channel-1"],"pinnedAgentIds":["agent-1"],"pinnedOrder":["channel-1","agent-1"],"hiddenDmIds":["dm-2"],"channelPanelTabOrder":["channels","threads"],"agentPanelTabOrder":["agents","dms"]}""",
                onboardingUpdateJson = """{"setupModalReminderOptOut":true}""",
                translationUpdateJson = """{"translationEnabled":false,"translationAvailable":true,"canManageTranslation":true}""",
                sidebarOrderUpdateJson = """{"channelOrder":["channel-2"],"agentOrder":[],"dmOrder":[],"channelSortMode":"az","dmSortMode":"manual","pinnedSortMode":"manual","pinnedChannelIds":[],"pinnedAgentIds":[],"pinnedOrder":[],"hiddenDmIds":[],"channelPanelTabOrder":["threads","channels"],"agentPanelTabOrder":["dms","agents"]}""",
                memberRoleUpdateJson = """{"ok":true}""",
                removeMemberJson = """{"ok":true}""",
                leaveServerJson = """{"ok":true}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val onboarding = repository.updateServerOnboarding(SettingsServerOnboardingUpdateRequest(setupModalReminderOptOut = true))
        val translation = repository.getServerTranslationSettings()
        val updatedTranslation = repository.updateServerTranslationSettings(SettingsServerTranslationUpdateRequest(translationEnabled = false))
        val sidebar = repository.getServerSidebarOrder()
        val updatedSidebar =
            repository.updateServerSidebarOrder(
                SettingsServerSidebarOrderUpdateRequest(
                    channelOrder = listOf("channel-2"),
                    channelSortMode = "az",
                    channelPanelTabOrder = listOf("threads", "channels"),
                    agentPanelTabOrder = listOf("dms", "agents")
                )
            )
        val memberRole = repository.updateServerMemberRole(SettingsServerMemberRoleUpdateRequest(memberId = "member-2", role = "admin"))
        val removedMember = repository.removeServerMember("member-3")
        val leftServer = repository.leaveServer()

        assertTrue(onboarding is DataResult.Content)
        assertEquals(true, onboarding.data.setupModalReminderOptOut)
        assertEquals("""{"setupModalReminderOptOut":true}""", network.onboardingBodies.single())
        assertTrue(translation is DataResult.Content)
        assertEquals(true, translation.data.translationEnabled)
        assertEquals("Enabled", translation.data.statusLabel)
        assertTrue(updatedTranslation is DataResult.Content)
        assertEquals(false, updatedTranslation.data.translationEnabled)
        assertEquals("""{"translationEnabled":false}""", network.translationBodies.single())
        assertTrue(sidebar is DataResult.Content)
        assertEquals(listOf("channel-1"), sidebar.data.channelOrder)
        assertEquals("recent", sidebar.data.dmSortMode)
        assertTrue(updatedSidebar is DataResult.Content)
        assertEquals(listOf("channel-2"), updatedSidebar.data.channelOrder)
        assertEquals("""{"channelOrder":["channel-2"],"channelSortMode":"az","channelPanelTabOrder":["threads","channels"],"agentPanelTabOrder":["dms","agents"]}""", network.sidebarBodies.single())
        assertTrue(memberRole is DataResult.Content)
        assertEquals("member-2", network.updatedMemberRoleMemberId)
        assertEquals("""{"role":"admin"}""", network.memberRoleBodies.single())
        assertTrue(removedMember is DataResult.Content)
        assertEquals("member-3", network.deletedMemberId)
        assertTrue(leftServer is DataResult.Content)
        assertEquals(1, network.leaveServerCallCount)
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
        assertEquals("computers_list_contract_mismatch", nestedWrapperResult.error.code)
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
                    {"machines":[{"id":"machine-1","name":"MacBook","hostname":"mac.local","os":"macOS","daemonVersion":"1.4.2","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"lastHeartbeat":"2026-05-07T01:00:00Z","createdAt":"2026-04-01T00:00:00Z"}]}
                """.trimIndent(),
                membersJson = """
                    [
                      {"userId":"agent-1","name":"settings-integrator","displayName":"Settings Integrator","description":"Settings real-data wiring","avatarUrl":"pixel:diamond","role":"member"}
                    ]
                """.trimIndent(),
                workspacesJson = """
                    [{"directoryName":"Slock","totalSizeBytes":4096,"fileCount":12,"status":"active","lastModified":"2026-05-07T01:01:00Z"}]
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
        // #74: server read-model fields must propagate to SettingsComputerSnapshot
        // so the React-aligned detail page can render Detected runtimes / Created / etc.
        assertEquals(7L, detail.computer.statusVersion)
        assertEquals("sk_machine_abc", detail.computer.apiKeyPrefix)
        assertEquals(listOf("claude", "codex"), detail.computer.runtimes)
        assertEquals("2026-04-01T00:00:00Z", detail.computer.createdAt)
        assertEquals("2026-05-07T01:00:00Z", detail.computer.lastHeartbeat)
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
        // 503 from server route means "machine offline" (fly-replay didn't kick in),
        // see server/routes/servers.ts runtime-models handler — surfaces as
        // structured "machine_offline" code so the Settings page can show retry copy
        // for that specific case instead of generic server_unavailable.
        assertEquals("machine_offline", detail.workspacesError?.code)
        assertEquals(true, detail.workspacesError?.retryable)
        assertEquals("server_forbidden", detail.agentsError?.code)
        assertEquals(false, detail.agentsError?.retryable)
        assertTrue(logs.any { it.contains("section=workspaces result=error code=machine_offline") })
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
        assertEquals("sk_machine_a", register.data.apiKeyPrefix)
        assertTrue(rename is DataResult.Content)
        assertEquals("Builder Mac 2", rename.data.name)
        assertTrue(rotate is DataResult.Content)
        assertEquals("sk_machine_r", rotate.data.apiKeyPrefix)
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
        private val onboardingUpdateJson: String = onboardingJson,
        private val translationJson: String = """{"translationEnabled":false,"translationAvailable":false,"canManageTranslation":false}""",
        private val translationUpdateJson: String = translationJson,
        private val sidebarOrderJson: String = """{"channelOrder":[],"agentOrder":[],"dmOrder":[],"channelSortMode":"manual","dmSortMode":"manual","pinnedSortMode":"manual","pinnedChannelIds":[],"pinnedAgentIds":[],"pinnedOrder":[],"hiddenDmIds":[],"channelPanelTabOrder":[],"agentPanelTabOrder":[]}""",
        private val sidebarOrderUpdateJson: String = sidebarOrderJson,
        private val inviteJson: String = """{"id":"invite-1","email":"new@slock.ai"}""",
        private val invitesJson: String = """[]""",
        private val notificationSettingsJson: String = """{"serverPushMuted":false}""",
        private val createJoinLinkJson: String =
            """{"token":"join-token","link":{"id":"join-1","createdAt":"2026-05-09T00:00:00Z","expiresAt":null,"maxUses":null,"useCount":0}}""",
        private val joinLinksJson: String = """[]""",
        private val inviteDeleteJson: String = """{"ok":true}""",
        private val joinLinkDeleteJson: String = """{"ok":true}""",
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
        private val memberRoleUpdateJson: String = commandJson,
        private val removeMemberJson: String = commandJson,
        private val leaveServerJson: String = commandJson,
        private val machinesResponseCode: Int? = null,
        private val workspacesResponseCode: Int? = null,
        private val agentsResponseCode: Int? = null,
        private val responseCode: Int = 200
    ) : SlockNetworkClient {
        private var currentServerPushMuted = notificationSettingsJson.contains("true")
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
        var deletedInviteId: String? = null
            private set
        var deletedJoinLinkId: String? = null
            private set
        var deletedMemberId: String? = null
            private set
        var deletedWorkspaceDirectoryName: String? = null
            private set
        var updatedMemberRoleMemberId: String? = null
            private set
        var leaveServerCallCount: Int = 0
            private set
        val accountUpdateBodies = mutableListOf<String>()
        val inviteBodies = mutableListOf<String>()
        val joinLinkBodies = mutableListOf<String>()
        val notificationSettingsBodies = mutableListOf<String>()
        val onboardingBodies = mutableListOf<String>()
        val translationBodies = mutableListOf<String>()
        val sidebarBodies = mutableListOf<String>()
        val memberRoleBodies = mutableListOf<String>()
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
                normalized.matches(Regex("""servers/[^/]+/onboarding-settings""")) && method == "PATCH" -> {
                    onboardingBodies += bodyText.orEmpty()
                    response(onboardingUpdateJson)
                }
                normalized.matches(Regex("""servers/[^/]+/translation-settings""")) && method == "GET" ->
                    response(translationJson)
                normalized.matches(Regex("""servers/[^/]+/translation-settings""")) && method == "PATCH" -> {
                    translationBodies += bodyText.orEmpty()
                    response(translationUpdateJson)
                }
                normalized.matches(Regex("""servers/[^/]+/sidebar-order""")) && method == "GET" ->
                    response(sidebarOrderJson)
                normalized.matches(Regex("""servers/[^/]+/sidebar-order""")) && method == "PATCH" -> {
                    sidebarBodies += bodyText.orEmpty()
                    response(sidebarOrderUpdateJson)
                }
                normalized.matches(Regex("""servers/[^/]+/notification-settings""")) && method == "GET" ->
                    response("""{"serverPushMuted":$currentServerPushMuted}""")
                normalized.matches(Regex("""servers/[^/]+/notification-settings""")) && method == "PATCH" -> {
                    notificationSettingsBodies += bodyText.orEmpty()
                    currentServerPushMuted = bodyText?.contains("true") == true
                    response("""{"serverPushMuted":$currentServerPushMuted}""")
                }
                normalized.matches(Regex("""servers/[^/]+""")) && method == "PATCH" -> {
                    val serverId = normalized.split("/")[1]
                    response("""{"id":"$serverId","name":"Slock Android"}""")
                }
                normalized.matches(Regex("""servers/[^/]+/invites""")) && method == "POST" -> {
                    inviteBodies += bodyText.orEmpty()
                    response(inviteJson)
                }
                normalized.matches(Regex("""servers/[^/]+/invites""")) && method == "GET" ->
                    response(invitesJson)
                normalized.matches(Regex("""servers/[^/]+/invites/[^/]+""")) && method == "DELETE" -> {
                    deletedInviteId = normalized.split("/")[3]
                    response(inviteDeleteJson)
                }
                normalized.matches(Regex("""servers/[^/]+/join-links""")) && method == "POST" -> {
                    joinLinkBodies += bodyText.orEmpty()
                    response(createJoinLinkJson)
                }
                normalized.matches(Regex("""servers/[^/]+/join-links""")) && method == "GET" ->
                    response(joinLinksJson)
                normalized.matches(Regex("""servers/[^/]+/join-links/[^/]+""")) && method == "DELETE" -> {
                    deletedJoinLinkId = normalized.split("/")[3]
                    response(joinLinkDeleteJson)
                }
                normalized.matches(Regex("""servers/[^/]+/members/[^/]+""")) && method == "PATCH" -> {
                    updatedMemberRoleMemberId = normalized.split("/")[3]
                    memberRoleBodies += bodyText.orEmpty()
                    response(memberRoleUpdateJson)
                }
                normalized.matches(Regex("""servers/[^/]+/members/[^/]+""")) && method == "DELETE" -> {
                    deletedMemberId = normalized.split("/")[3]
                    response(removeMemberJson)
                }
                normalized.matches(Regex("""servers/[^/]+/leave""")) && method == "POST" -> {
                    leaveServerCallCount += 1
                    response(leaveServerJson)
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
