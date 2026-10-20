package build.raft.app.data

import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.network.MultipartFile
import build.raft.app.resolve
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
                              "gravatarHash": "settingshash",
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
    fun accountMapsEnabledProvidersAndLinkedIdentities() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    accountJson = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User","emailVerified":true}""",
                    authProvidersJson = """
                        {"providers":[
                          {"id":"google","name":"Google","enabled":true},
                          {"id":"github","name":"GitHub","enabled":true},
                          {"id":"apple","name":"Apple","enabled":true},
                          {"id":"disabled","name":"Disabled","enabled":false}
                        ]}
                    """.trimIndent(),
                    authIdentitiesJson = """
                        {"identities":[
                          {"provider":"google","providerUserId":"google-1","email":"user@gmail.com"}
                        ]}
                    """.trimIndent()
                )
            )

        val result = repository.getAccount()

        assertTrue(result is DataResult.Content)
        assertEquals(listOf("password", "google", "github", "apple"), result.data.connectedAccounts.map { it.providerId })
        assertEquals(ConnectedAccountStatus.Connected, result.data.connectedAccounts.single { it.providerId == "google" }.status)
        assertEquals("user@gmail.com", result.data.connectedAccounts.single { it.providerId == "google" }.identifier)
        assertEquals(ConnectedAccountStatus.NotConnected, result.data.connectedAccounts.single { it.providerId == "github" }.status)
        assertEquals(ConnectedAccountStatus.NotConnected, result.data.connectedAccounts.single { it.providerId == "apple" }.status)
    }

    @Test
    fun accountPasswordAvatarAndProviderLinkUseTypedEndpoints() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                accountJson = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User","emailVerified":true}""",
                providerLinkStartJson = """{"url":"https://raft.build/auth/github/link"}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val password =
            repository.changeAccountPassword(
                AccountPasswordChangeRequest(currentPassword = "old-password", newPassword = "new-password")
            )
        val avatar =
            repository.uploadAccountAvatar(
                AccountAvatarUploadRequest(
                    PlatformFile(
                        id = "avatar-1",
                        fileName = "avatar.png",
                        mimeType = "image/png",
                        bytes = "image".encodeToByteArray(),
                        previewUri = "file://avatar.png"
                    )
                )
            )
        val link =
            repository.startAccountProviderLink(
                AccountProviderLinkStartRequest(providerId = "GitHub!!", returnTo = "slock://settings/account")
            )

        assertTrue(password is DataResult.Content)
        assertTrue(avatar is DataResult.Content)
        assertTrue(link is DataResult.Content)
        assertEquals("""{"currentPassword":"old-password","newPassword":"new-password"}""", network.accountUpdateBodies.single())
        assertEquals("avatar", network.avatarFileNames.single())
        assertEquals("github", network.providerLinkProviderIds.single())
        assertEquals("""{"returnTo":"slock://settings/account"}""", network.providerLinkBodies.single())
        assertEquals("https://raft.build/auth/github/link", link.data.url)
    }

    @Test
    fun connectedAppsMapsMarketplaceAndCreateSecretOnce() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                connectedAppClientsJson = """
                    [
                      {
                        "id":"client-1",
                        "serverId":"server-1",
                        "clientId":"local-client",
                        "appType":"server_local",
                        "publishStatus":"draft",
                        "category":"productivity",
                        "dataAccessSummary":"Profile",
                        "name":"Local Client",
                        "description":"Private integration",
                        "homepageUrl":"https://local.example/app",
                        "returnUrl":"https://local.example/oauth/callback"
                      }
                    ]
                """.trimIndent(),
                connectedAppBuiltInsJson = """
                    [
                      {
                        "id":"built-in-1",
                        "clientId":"raft-actions",
                        "name":"Raft Actions",
                        "description":"Built-in action callbacks",
                        "homepageUrl":"https://raft.build"
                      }
                    ]
                """.trimIndent(),
                connectedAppMarketplaceJson = """
                    [
                      {
                        "id":"market-1",
                        "clientId":"calendar",
                        "name":"Calendar",
                        "category":"productivity",
                        "publisherName":"Raft",
                        "description":"Sync reminders",
                        "homepageUrl":"https://calendar.example",
                        "returnUrl":"https://calendar.example/callback",
                        "installedAt":"2026-06-01T00:00:00Z",
                        "dataAccessSummary":"Profile and reminders"
                      }
                    ]
                """.trimIndent(),
                connectedAppCreateJson = """
                    {
                      "client": {
                        "id":"client-created",
                        "serverId":"server-1",
                        "clientId":"mobile-client",
                        "appType":"server_local",
                        "publishStatus":"draft",
                        "category":"productivity",
                        "name":"Mobile App",
                        "returnUrl":"https://mobile.example/callback"
                      },
                      "clientSecret":"secret_once"
                    }
                """.trimIndent()
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val apps = repository.getConnectedApps()

        assertTrue(apps is DataResult.Content)
        assertEquals(listOf("Calendar"), apps.data.marketplace.map { it.name })
        assertEquals(listOf("Raft Actions"), apps.data.builtIn.map { it.name })
        assertEquals(listOf("market-1", "client-1"), apps.data.installed.map { it.id })
        assertEquals(listOf("client-1"), apps.data.registered.map { it.id })
        assertEquals(
            listOf(false, true),
            apps.data.installed.map { it.editable }
        )
        assertEquals(
            listOf("marketplace", "private"),
            apps.data.installed.map { it.origin }
        )
        assertEquals(true, apps.data.canManage)

        val created =
            repository.registerConnectedApp(
                SettingsConnectedAppRegisterRequest(
                    name = "Mobile App",
                    clientId = "mobile-client",
                    returnUrl = "https://mobile.example/callback"
                )
            )

        assertTrue(created is DataResult.Content)
        assertEquals("secret_once", created.data.oneTimeClientSecret?.clientSecret)
        assertEquals("""{"name":"Mobile App","clientId":"mobile-client","returnUrl":"https://mobile.example/callback"}""", network.connectedAppCreateBodies.single())
    }

    @Test
    fun connectedAppsLogoUploadAndClearUseTypedEndpoints() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                connectedAppClientsJson = """
                    [
                      {
                        "id":"client-1",
                        "serverId":"server-1",
                        "clientId":"local-client",
                        "appType":"server_local",
                        "publishStatus":"draft",
                        "category":"productivity",
                        "name":"Local Client"
                      }
                    ]
                """.trimIndent()
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val uploaded =
            repository.uploadConnectedAppLogo(
                SettingsConnectedAppLogoUploadRequest(
                    id = "client-1",
                    file =
                        PlatformFile(
                            id = "logo-1",
                            fileName = "logo.png",
                            mimeType = "image/png",
                            bytes = "logo".encodeToByteArray()
                        )
                )
            )
        val cleared = repository.clearConnectedAppLogo("client-1")

        assertTrue(uploaded is DataResult.Content)
        assertEquals("https://cdn.example/logo.png", uploaded.data.registered.single().logoUrl)
        assertTrue(cleared is DataResult.Content)
        assertNull(cleared.data.registered.single().logoUrl)
        assertEquals(listOf("client-1"), network.uploadedConnectedAppLogoIds)
        assertEquals(listOf("logo"), network.connectedAppLogoFieldNames)
        assertEquals(listOf("logo.png"), network.connectedAppLogoFileNames)
        assertEquals("client-1", network.clearedConnectedAppLogoId)
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
        assertEquals(
            listOf("browser", "en", "zh-cn", "zh-tw", "ja", "ko", "es", "fr", "de", "pt-br", "it"),
            languageRegion.data.browserLanguage.options.map { it.key }
        )
        assertEquals("Asia/Shanghai", languageRegion.data.timezone.selectedKey)
        assertEquals("12h", languageRegion.data.timeFormat.selectedKey)
    }

    @Test
    fun languageRegionDisplayPreferenceRemainsEditableWhenServerTranslationDisabled() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    accountJson = """
                        {
                          "id":"user-1",
                          "email":"user@slock.ai",
                          "name":"slock-user",
                          "preferredTranslationDisplay":"original"
                        }
                    """.trimIndent(),
                    translationJson = """{"translationEnabled":false,"translationAvailable":false,"canManageTranslation":false}"""
                )
            )

        val languageRegion = repository.getLanguageRegionSettings()

        assertTrue(languageRegion is DataResult.Content)
        assertEquals(false, languageRegion.data.serverTranslation.enabled)
        assertEquals("original", languageRegion.data.translationDisplay.selectedKey)
        assertTrue(languageRegion.data.translationDisplay.available)
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
        val translationDisplay =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTranslationDisplay = "original")
            )

        assertTrue(timezone is DataResult.Content)
        assertTrue(timeFormat is DataResult.Content)
        assertTrue(clearLanguage is DataResult.Content)
        assertTrue(translationDisplay is DataResult.Content)
        assertEquals(
            listOf(
                """{"preferredTimezone":"Asia/Tokyo"}""",
                """{"preferredTimeFormat":"24h"}""",
                """{"preferredLanguage":null}""",
                """{"preferredTranslationDisplay":"original"}"""
            ),
            network.accountUpdateBodies
        )
    }

    @Test
    fun timeFormatWriteUpdatesCanonicalSessionWithoutReplacingTokens() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(
            sampleSession().copy(
                    user =
                        sampleSession().user.copy(
                            preferredTimeFormat = "24h",
                            timeFormat = "24h",
                        )
                )
        )
        val metadataEvent =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000L) {
                    store.authSessionEvents.first { it is AuthSessionEvent.BindingMetadataChanged }
                }
            }
        val repository =
            RealSettingsRepository(
                store,
                FakeSettingsNetworkClient(
                    accountJson =
                        """{"id":"user-1","email":"user@slock.ai","name":"slock-user","preferredTimeFormat":"12h"}"""
                )
            )

        val result =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimeFormat = "12h")
            )

        assertTrue(result is DataResult.Content)
        assertIs<AuthSessionEvent.BindingMetadataChanged>(metadataEvent.await())
        val persisted = store.readSession()
        assertEquals("12h", persisted?.user?.preferredTimeFormat)
        assertEquals("12h", persisted?.user?.timeFormat)
        assertEquals("sample-access", persisted?.accessToken)
        assertEquals("sample-refresh", persisted?.refreshToken)
    }

    @Test
    fun unrelatedLanguageRegionWriteCannotRollbackCanonicalTimeFormat() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession().copy(
                    user =
                        sampleSession().user.copy(
                            preferredTimeFormat = "12h",
                            timeFormat = "12h",
                        )
                )
            )
        val repository =
            RealSettingsRepository(
                store,
                FakeSettingsNetworkClient(
                    accountJson =
                        """{"id":"user-1","email":"user@slock.ai","name":"slock-user","preferredTimezone":"Asia/Tokyo","preferredTimeFormat":"24h"}"""
                )
            )

        val result =
            repository.updateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = "Asia/Tokyo")
            )

        assertTrue(result is DataResult.Content)
        assertEquals("12h", store.readSession()?.user?.preferredTimeFormat)
        assertEquals("12h", store.readSession()?.user?.timeFormat)
    }

    @Test
    fun appearanceUsesAuthMePreferredMessageBodyFontSize() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                accountJson = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","preferredMessageBodyFontSize":"sm"}"""
            )
        val published = mutableListOf<Pair<String, String>>()
        val repository =
            RealSettingsRepository(
                sessionStore = sampleStore(),
                networkClient = network,
                onAppearanceSnapshot = { ownerId, snapshot ->
                    published += ownerId to snapshot.messageBodyFontSize.selectedKey
                },
            )

        val loaded = repository.getAppearanceSettings()
        val updated = repository.updateAppearanceSettings(SettingsAppearanceUpdateRequest("lg"))

        assertTrue(loaded is DataResult.Content)
        assertEquals("sm", loaded.data.messageBodyFontSize.selectedKey)
        assertTrue(updated is DataResult.Content)
        assertEquals("sm", updated.data.messageBodyFontSize.selectedKey)
        assertEquals("""{"preferredMessageBodyFontSize":"lg"}""", network.accountUpdateBodies.single())
        assertEquals(listOf("user-1" to "sm", "user-1" to "sm"), published)
    }

    @Test
    fun billingMapsReadablePlanStateAndRequiresOwnerPermission() = runBlocking {
        val billing =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    usageJson = """{"machines":1,"agents":10,"channels":12}""",
                    billingSubscriptionJson = """{"subscription":{"status":"active","currentPeriodEnd":"2026-06-01T00:00:00Z","cancelAtPeriodEnd":true},"stripeConfigured":true,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}"""
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
        assertEquals("For teams scaling agent collaboration.", billingSnapshot.currentPlan.description)
        assertTrue(billingSnapshot.currentPlan.includedFeatures.contains("Unlimited message history"))
        assertTrue(billingSnapshot.currentPlan.excludedFeatures.isEmpty())
        assertEquals(listOf("cancel-scheduled"), billingSnapshot.notices.map { it.key })
        assertEquals(listOf("portal", "cancel"), billingSnapshot.actions.map { it.key })
        assertTrue(billingSnapshot.actions.first { it.key == "portal" }.enabled)
        assertTrue(!billingSnapshot.actions.first { it.key == "cancel" }.enabled)
        assertEquals(listOf("Hobby", "Team", "Business"), billingSnapshot.plans.map { it.name })
        assertTrue(billingSnapshot.plans.single { it.name == "Team" }.current)
        assertTrue(billingSnapshot.plans.any { it.comingSoon })
        assertTrue(denied is DataResult.NoPermission)
        assertEquals("billing_owner_required", denied.blocker.code)
    }

    @Test
    fun billingMapsDowngradeAndFounderNoticeStates() = runBlocking {
        val downgradeSession =
            sampleSession().copy(
                servers =
                    sampleSession().servers.map {
                        it.copy(plan = "free", planDowngradedAt = "2026-06-18T00:00:00Z")
                    }
            )
        val downgrade =
            RealSettingsRepository(
                sampleStore(downgradeSession),
                FakeSettingsNetworkClient(
                    usageJson = """{"machines":4,"agents":12,"channels":8}""",
                    billingSubscriptionJson = """{"subscription":null,"stripeConfigured":false,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}"""
                )
            ).getBilling()
        val founderSession =
            sampleSession().copy(
                servers = sampleSession().servers.map { it.copy(plan = "founder") }
            )
        val founder =
            RealSettingsRepository(
                sampleStore(founderSession),
                FakeSettingsNetworkClient(
                    usageJson = """{"machines":4,"agents":12,"channels":8}""",
                    billingSubscriptionJson = """{"subscription":null,"stripeConfigured":false,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}"""
                )
            ).getBilling()

        assertTrue(downgrade is DataResult.Content)
        assertEquals("Hobby", downgrade.data.currentPlan.planName)
        assertEquals(listOf("downgrade"), downgrade.data.notices.map { it.key })
        assertEquals("warning", downgrade.data.notices.single().tone)
        assertEquals(listOf("Agents over limit", "Computers over limit", "Channels over limit"), downgrade.data.notices.single().rows.map { it.label })
        // Like web billingControls: Free includes Joint Channels with free-server slots.
        assertFalse(downgrade.data.currentPlan.excludedFeatures.any { it.contains("Joint", ignoreCase = true) })
        assertTrue(
            downgrade.data.currentPlan.includedFeatures.contains(
                "Use Joint Channels (each Joint Channel has 2 free-server slots)"
            )
        )

        assertTrue(founder is DataResult.Content)
        assertEquals("Founder", founder.data.currentPlan.planName)
        assertEquals(listOf("founder"), founder.data.notices.map { it.key })
        assertEquals(listOf("Everything without any limitations"), founder.data.currentPlan.includedFeatures)
        assertTrue(founder.data.currentPlan.excludedFeatures.isEmpty())
    }

    @Test
    fun settingsRepositoryMapsAuthPermissionAndServerErrors() = runBlocking {
        val noSession = RealSettingsRepository(InMemoryRaftAuthSessionStore(null), FakeSettingsNetworkClient()).getAccount()
        val forbidden = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(responseCode = 403)).getAccount()
        val unavailable = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(responseCode = 503)).getAccount()
        val parse = RealSettingsRepository(sampleStore(), FakeSettingsNetworkClient(accountJson = """{"user":""")).getAccount()

        assertTrue(noSession is DataResult.NoPermission)
        assertEquals("auth_required", noSession.blocker.code)
        assertTrue(forbidden is DataResult.NoPermission)
        assertEquals("server_forbidden", forbidden.blocker.code)
        assertEquals("failed", forbidden.blocker.serverMessage)
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
                          {"userId":"admin-1","displayName":"Admin User","email":"admin@slock.ai","role":"admin"},
                          {"userId":"member-1","displayName":"Member User","email":"member@slock.ai","role":"member"}
                        ]
                    """.trimIndent(),
                    usageJson = """{"machines":1,"agents":10,"channels":12}""",
                    billingSubscriptionJson = """{"subscription":{"status":"active","currentPeriodEnd":"2026-06-01T00:00:00Z"},"stripeConfigured":true}""",
                    agreementJson = """{"enabled":true,"agreement":{"title":"Join Agreement","bodyMarkdown":"Be kind."}}""",
                    onboardingJson = """{"setupModalReminderOptOut":true,"onboardingAgentId":"agent-1","agentAllChannelGreetingEnabled":false}""",
                    agentsJson = """[{"id":"agent-1","name":"onboarding-agent","displayName":"Onboarding Agent","status":"idle"}]""",
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
            listOf("admins", "billing", "pending_invites", "join_links", "pre_join_agreement", "onboarding_agent", "member_permissions", "translation", "sidebar_order", "danger"),
            serverSnapshot.sections.map { it.key }
        )
        assertEquals(listOf("Owner", "Admin User", "Member User"), serverSnapshot.admins.map { it.name })
        assertEquals(listOf("Owner", "Admin", "Member"), serverSnapshot.admins.map { it.role })
        assertEquals("2 owner/admin members", serverSnapshot.sections.first { it.key == "admins" }.detail)
        assertTrue(serverSnapshot.usageRows.any { it.label == "Billing Status" && it.value == "Active" })
        assertTrue(serverSnapshot.usageRows.any { it.label == "Computers" && it.value == "1" })
        assertEquals(true, serverSnapshot.agreement?.enabled)
        assertEquals("Join Agreement", serverSnapshot.agreement?.title)
        assertEquals(true, serverSnapshot.onboarding?.setupModalReminderOptOut)
        assertEquals("agent-1", serverSnapshot.onboarding?.onboardingAgentId)
        assertEquals(false, serverSnapshot.onboarding?.agentAllChannelGreetingEnabled)
        assertEquals(listOf("Onboarding Agent"), serverSnapshot.onboarding?.agents?.map { it.displayName })
        assertEquals(false, serverSnapshot.profile.hideHumansFromMembers)
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
        assertEquals(ServerPushMode.All.wireName, mobile.data.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.data.sendTestEnabled)
    }

    @Test
    fun mobileMuteInviteAndJoinLinkActionsUseConfirmedContracts() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                notificationSettingsJson = """{"serverPushMuted":false,"serverPushMentionsOnly":false,"serverPushMode":"all"}""",
                inviteDeleteJson = """{"ok":true}""",
                createJoinLinkJson = """{"token":"created-token","link":{"id":"join-2","createdAt":"2026-05-09T00:00:00Z","expiresAt":"2026-06-01T00:00:00Z","maxUses":5,"useCount":0}}""",
                joinLinkDeleteJson = """{"ok":true}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val mentionsOnly = repository.setServerNotificationMode(ServerPushMode.Mentions)
        val revokedInvite = repository.revokeServerInvite("invite-1")
        val createdJoinLink = repository.createServerJoinLink(
            SettingsServerJoinLinkCreateRequest(
                expiresAt = "2026-06-01T00:00:00Z",
                maxUses = 5
            )
        )
        val revokedJoinLink = repository.revokeServerJoinLink("join-2")

        assertTrue(mentionsOnly is DataResult.Content)
        assertEquals(false, mentionsOnly.data.muteServer.enabled)
        assertEquals(ServerPushMode.Mentions.wireName, mentionsOnly.data.serverNotificationMode.selectedKey)
        assertEquals("""{"serverPushMode":"mentions"}""", network.notificationSettingsBodies.single())
        assertTrue(revokedInvite is DataResult.Content)
        assertEquals("invite-1", network.deletedInviteId)
        assertTrue(createdJoinLink is DataResult.Content)
        assertEquals("/join/created-token", createdJoinLink.data.url)
        assertEquals("""{"expiresAt":"2026-06-01T00:00:00Z","maxUses":5}""", network.joinLinkBodies.single())
        assertTrue(revokedJoinLink is DataResult.Content)
        assertEquals("join-2", network.deletedJoinLinkId)
    }

    @Test
    fun iosPushPreviewModeUsesCanonicalIndependentPatch() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                notificationSettingsJson =
                    """{"serverPushMuted":false,"serverPushMentionsOnly":false,"serverPushMode":"all","pushPreviewMode":"full"}"""
            )
        val repository = RealSettingsRepository(sampleStore(), network)

        val initial = assertIs<DataResult.Content<SettingsMobileSnapshot>>(repository.getMobileSettings()).data
        assertTrue(initial.pushMessagePreviews.enabled)
        assertTrue(initial.pushMessagePreviews.available)

        val updated = assertIs<DataResult.Content<SettingsMobileSnapshot>>(
            repository.setPushPreviewMode(PushPreviewMode.Private)
        ).data

        assertFalse(updated.pushMessagePreviews.enabled)
        assertTrue(updated.pushMessagePreviews.available)
        assertEquals(ServerPushMode.All.wireName, updated.serverNotificationMode.selectedKey)
        assertEquals("""{"pushPreviewMode":"private"}""", network.notificationSettingsBodies.single())
    }

    @Test
    fun legacyServerDoesNotAdvertisePushPreviewPrivacy() = runBlocking {
        val network = FakeSettingsNetworkClient(notificationSettingsJson = """{"serverPushMuted":false}""")
        val repository = RealSettingsRepository(sampleStore(), network)

        val initial = assertIs<DataResult.Content<SettingsMobileSnapshot>>(repository.getMobileSettings()).data
        assertTrue(initial.pushMessagePreviews.enabled)
        assertFalse(initial.pushMessagePreviews.available)

        val unsupported = repository.setPushPreviewMode(PushPreviewMode.Private)
        assertIs<DataResult.Error>(unsupported)
        assertEquals("settings_push_preview_mode_unsupported", unsupported.error.code)
        assertTrue(network.notificationSettingsBodies.isEmpty())
    }

    @Test
    fun legacyServerKeepsAllAndNoneButDoesNotExposeMentions() = runBlocking {
        val network = FakeSettingsNetworkClient(notificationSettingsJson = """{"serverPushMuted":false}""")
        val repository = RealSettingsRepository(sampleStore(), network)

        val initial = assertIs<DataResult.Content<SettingsMobileSnapshot>>(repository.getMobileSettings()).data
        assertEquals(listOf("all", "none"), initial.serverNotificationMode.options.map { it.key })

        val unsupported = repository.setServerNotificationMode(ServerPushMode.Mentions)
        assertIs<DataResult.Error>(unsupported)
        assertEquals("settings_server_notification_mentions_unsupported", unsupported.error.code)
        assertTrue(network.notificationSettingsBodies.isEmpty())

        assertIs<DataResult.Content<SettingsMobileSnapshot>>(repository.setServerNotificationMode(ServerPushMode.None))
        assertEquals("""{"serverPushMuted":true}""", network.notificationSettingsBodies.single())
    }

    @Test
    fun presentUnknownCanonicalServerModeFailsClosedWithoutLegacyFallback() {
        val diagnostics = mutableListOf<String>()
        val parsed = parseSharedSettingsServerNotificationSettings(
            """{"serverPushMuted":false,"serverPushMode":"future_restrictive"}""",
            diagnostics::add
        )

        assertEquals(null, parsed)
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.single().contains("server_push_mode"))
    }

    @Test
    fun presentUnknownPushPreviewModeFailsClosed() {
        val diagnostics = mutableListOf<String>()
        val parsed = parseSharedSettingsServerNotificationSettings(
            """{"serverPushMuted":false,"serverPushMode":"all","pushPreviewMode":"future"}""",
            diagnostics::add
        )

        assertEquals(null, parsed)
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.single().contains("push_preview_mode"))
    }

    @Test
    fun serverManagementContractsUseConfirmedShapes() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                agreementJson = """{"enabled":false,"agreement":null}""",
                agreementUpdateJson = """{"enabled":true,"agreement":{"title":"Rules","bodyMarkdown":"Be excellent."}}""",
                onboardingJson = """{"setupModalReminderOptOut":false,"onboardingAgentId":null,"agentAllChannelGreetingEnabled":true}""",
                translationJson = """{"translationEnabled":true,"translationAvailable":true,"canManageTranslation":true}""",
                sidebarOrderJson = """{"channelOrder":["channel-1"],"agentOrder":["agent-1"],"dmOrder":["dm-1"],"channelSortMode":"manual","dmSortMode":"recent","pinnedSortMode":"az","pinnedChannelIds":["channel-1"],"pinnedAgentIds":["agent-1"],"pinnedOrder":["channel-1","agent-1"],"pinned":[{"kind":"channel","id":"channel-1"},{"kind":"agent","id":"agent-1"},{"kind":"human","id":"user-1"},{"kind":"invalid","id":"ignored"}],"hiddenDmIds":["dm-2"],"channelPanelTabOrder":["channels","threads"],"agentPanelTabOrder":["agents","dms"],"customSections":[{"id":"sec-1","name":"My Project","emoji":"🚀","sortMode":"az"},{"id":"","name":"bad","sortMode":"manual"},{"id":"sec-2","name":"No Sort","sortMode":"bogus"}],"sectionOrder":["system:dms","sec-1","bogus-id"],"sectionPlacements":[{"kind":"channel","id":"channel-1","sectionId":"sec-1","position":0},{"kind":"bogus","id":"x","sectionId":"sec-1","position":1}],"sectionsVersion":7}""",
                serverOrderJson = """{"serverOrder":["server-2","server-1"]}""",
                onboardingUpdateJson = """{"setupModalReminderOptOut":false,"onboardingAgentId":"agent-1","agentAllChannelGreetingEnabled":false}""",
                translationUpdateJson = """{"translationEnabled":false,"translationAvailable":true,"canManageTranslation":true}""",
                sidebarOrderUpdateJson = """{"channelOrder":["channel-2"],"agentOrder":[],"dmOrder":[],"channelSortMode":"az","dmSortMode":"manual","pinnedSortMode":"manual","pinnedChannelIds":[],"pinnedAgentIds":[],"pinnedOrder":[],"hiddenDmIds":[],"channelPanelTabOrder":["threads","channels"],"agentPanelTabOrder":["dms","agents"]}""",
                serverOrderUpdateJson = """{"serverOrder":["server-1","server-2"]}""",
                memberRoleUpdateJson = """{"ok":true}""",
                removeMemberJson = """{"ok":true}""",
                leaveServerJson = """{"ok":true}"""
        )
        val repository = RealSettingsRepository(sampleStore(), network)

        val agreement = repository.updateServerAgreement(SettingsServerAgreementUpdateRequest(enabled = true, title = "Rules", bodyMarkdown = "Be excellent."))
        val onboarding =
            repository.updateServerOnboarding(
                SettingsServerOnboardingUpdateRequest(
                    onboardingAgentId = "agent-1",
                    agentAllChannelGreetingEnabled = false
                )
            )
        val translation = repository.getServerTranslationSettings()
        val updatedTranslation = repository.updateServerTranslationSettings(SettingsServerTranslationUpdateRequest(translationEnabled = false))
        val sidebar = repository.getServerSidebarOrder()
        val serverOrder = repository.getServerOrder()
        val updatedSidebar =
            repository.updateServerSidebarOrder(
                SettingsServerSidebarOrderUpdateRequest(
                    channelOrder = listOf("channel-2"),
                    channelSortMode = "az",
                    channelPanelTabOrder = listOf("threads", "channels"),
                    agentPanelTabOrder = listOf("dms", "agents")
                )
            )
        val updatedServerOrder =
            repository.updateServerOrder(
                SettingsServerOrderUpdateRequest(
                    serverOrder = listOf("server-1", "server-2")
                )
            )
        val memberRole = repository.updateServerMemberRole(SettingsServerMemberRoleUpdateRequest(memberId = "member-2", role = "admin"))
        val removedMember = repository.removeServerMember("member-3")
        val leftServer = repository.leaveServer()

        val profile = repository.updateServerProfile(SettingsServerProfileUpdateRequest(hideHumansFromMembers = true))

        assertTrue(agreement is DataResult.Content)
        assertEquals(true, agreement.data.enabled)
        assertEquals("""{"enabled":true,"title":"Rules","bodyMarkdown":"Be excellent."}""", network.agreementBodies.single())
        assertTrue(onboarding is DataResult.Content)
        assertEquals("agent-1", onboarding.data.onboardingAgentId)
        assertEquals(false, onboarding.data.agentAllChannelGreetingEnabled)
        assertEquals("""{"onboardingAgentId":"agent-1","agentAllChannelGreetingEnabled":false}""", network.onboardingBodies.single())
        assertTrue(profile is DataResult.Content)
        assertEquals("""{"hideHumansFromMembers":true}""", network.profileBodies.single())
        assertTrue(translation is DataResult.Content)
        assertEquals(true, translation.data.translationEnabled)
        assertEquals("Enabled", translation.data.statusLabel)
        assertTrue(updatedTranslation is DataResult.Content)
        assertEquals(false, updatedTranslation.data.translationEnabled)
        assertEquals("""{"translationEnabled":false}""", network.translationBodies.single())
        assertTrue(sidebar is DataResult.Content)
        assertEquals(listOf("channel-1"), sidebar.data.channelOrder)
        assertEquals("recent", sidebar.data.dmSortMode)
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1"),
                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                SettingsServerSidebarPinnedItem(kind = "human", id = "user-1")
            ),
            sidebar.data.pinned
        )
        // task #512: custom sections parse, malformed entries drop, and the
        // section order self-repairs against system + surviving custom ids.
        assertEquals(
            listOf(SettingsServerSidebarCustomSection(id = "sec-1", name = "My Project", emoji = "🚀", sortMode = "az")),
            sidebar.data.customSections
        )
        assertEquals(
            listOf("system:dms", "sec-1", "system:pinned", "system:joint", "system:channels"),
            sidebar.data.sectionOrder
        )
        assertEquals(
            listOf(SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)),
            sidebar.data.sectionPlacements
        )
        assertEquals(7L, sidebar.data.sectionsVersion)
        assertTrue(serverOrder is DataResult.Content)
        assertEquals(listOf("server-2", "server-1"), serverOrder.data.serverOrder)
        assertTrue(updatedSidebar is DataResult.Content)
        assertEquals(listOf("channel-2"), updatedSidebar.data.channelOrder)
        assertEquals("""{"channelOrder":["channel-2"],"channelSortMode":"az","channelPanelTabOrder":["threads","channels"],"agentPanelTabOrder":["dms","agents"]}""", network.sidebarBodies.single())
        val updatedTypedPinned =
            repository.updateServerSidebarOrder(
                SettingsServerSidebarOrderUpdateRequest(
                    pinned = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"))
                )
            )
        assertTrue(updatedTypedPinned is DataResult.Content)
        assertEquals("""{"pinned":[{"kind":"agent","id":"agent-1"}]}""", network.sidebarBodies.last())
        // task #512: section updates send the OCC version and omit null emoji.
        val updatedSections =
            repository.updateServerSidebarOrder(
                SettingsServerSidebarOrderUpdateRequest(
                    customSections =
                        listOf(
                            SettingsServerSidebarCustomSection(id = "sec-1", name = "My Project", emoji = "🚀", sortMode = "az"),
                            SettingsServerSidebarCustomSection(id = "sec-2", name = "Plain")
                        ),
                    sectionOrder = listOf("system:pinned", "system:channels", "system:joint", "system:dms", "sec-1", "sec-2"),
                    sectionPlacements =
                        listOf(SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)),
                    sectionsVersion = 7
                )
            )
        assertTrue(updatedSections is DataResult.Content)
        assertEquals(
            """{"customSections":[{"id":"sec-1","name":"My Project","emoji":"🚀","sortMode":"az"},{"id":"sec-2","name":"Plain","sortMode":"manual"}],"sectionOrder":["system:pinned","system:channels","system:joint","system:dms","sec-1","sec-2"],"sectionPlacements":[{"kind":"channel","id":"channel-1","sectionId":"sec-1","position":0}],"sectionsVersion":7}""",
            network.sidebarBodies.last()
        )
        assertTrue(updatedServerOrder is DataResult.Content)
        assertEquals(listOf("server-1", "server-2"), updatedServerOrder.data.serverOrder)
        assertEquals("""{"serverOrder":["server-1","server-2"]}""", network.serverOrderBodies.single())
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
                        {"id":"machine-1","name":"Jiachengs-MacBook-Pro","hostname":"Jiachengs-MacBook-Pro","daemonVersion":"1.4.2","computerVersion":"0.0.61","isComputer":true,"status":"online"},
                        {"id":"machine-2","name":"Build host","daemonVersion":"1.3.0","status":"offline"}
                      ],
                      "latestDaemonVersion": "1.5.0",
                      "latestComputerVersion": "0.0.62"
                    }
                """.trimIndent()
            )
        val repository = RealSettingsRepository(sampleStore(), network, logger = logs::add, warningLogger = logs::add)

        val result = repository.getComputers()

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals("server-1", network.machinesServerId)
        assertEquals(true, snapshot.canAddComputer)
        assertEquals(null, snapshot.addComputerUnavailableReason)
        assertEquals("1.5.0", snapshot.latestDaemonVersion)
        assertEquals("0.0.62", snapshot.latestComputerVersion)
        assertEquals(2, snapshot.computers.size)
        assertEquals("Jiachengs-MacBook-Pro", snapshot.computers[0].name)
        assertEquals("1.4.2", snapshot.computers[0].daemonVersion)
        assertEquals("0.0.61", snapshot.computers[0].computerVersion)
        assertEquals(true, snapshot.computers[0].isComputer)
        assertEquals(true, snapshot.computers[0].online)
        assertEquals("machine-2", snapshot.computers[1].id)
        assertEquals(false, snapshot.computers[1].online)
        assertTrue(logs.any { it == "machines_fetch_start serverId=server-1" })
        assertTrue(logs.any { it == "machines_fetch_content serverId=server-1 count=2 canAdd=true" })
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
        assertFalse(billing.detail.contains("settings_billing_subscription_contract_mismatch"))
        assertEquals("", billing.detail)
        assertEquals(
            "The response could not be read. Please try again.",
            billing.errorPresentation?.resolve(englishErrorPresentationStringsForTest())
        )
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
    fun serverInvitePreservesCanonicalErrorTextFromProductionErrorBody() = runBlocking {
        val result =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    responseCode = 409,
                    errorJson = """{"error":"This person is already invited"}"""
                )
            ).createServerInvite(SettingsServerInviteCreateRequest("new@slock.ai"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("http_409", error.code)
        assertEquals("This person is already invited", error.serverMessage)
    }

    @Test
    fun inviteRouteErrorParserAcceptsOnlyItsExactErrorField() = runBlocking {
        val fixtures =
            listOf(
                "canonical-with-future-field" to
                    ("""{"error":"This person is already invited","future":{"retryAfter":30}}""" to
                        "This person is already invited"),
                "wrong-alias" to ("""{"message":"This person is already invited"}""" to null),
                "nested-error" to ("""{"error":{"message":"This person is already invited"}}""" to null),
                "malformed" to ("""{"error":"unterminated""" to null),
                "plain-text" to ("This person is already invited" to null),
                "blank" to ("" to null),
                "missing" to (null to null)
            )

        fixtures.forEach { (name, fixture) ->
            val (body, expectedMessage) = fixture
            val result =
                RealSettingsRepository(
                    sampleStore(),
                    FakeSettingsNetworkClient(responseCode = 409, errorJson = body)
                ).createServerInvite(SettingsServerInviteCreateRequest("new@slock.ai"))

            val error = assertIs<DataResult.Error>(result, name).error
            assertEquals("http_409", error.code, name)
            assertEquals(expectedMessage, error.serverMessage, name)
        }
    }

    @Test
    fun inviteAndJoinLinkRoutesShareTheConfirmedErrorEnvelope() = runBlocking {
        val repository =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    responseCode = 409,
                    errorJson = """{"error":"Invite policy rejected this request"}"""
                )
            )

        val results =
            listOf(
                repository.listServerJoinLinks("server-1"),
                repository.createServerInvite(SettingsServerInviteCreateRequest("new@slock.ai")),
                repository.createServerJoinLink(SettingsServerJoinLinkCreateRequest())
            )

        results.forEach { result ->
            val error = assertIs<DataResult.Error>(result).error
            assertEquals("http_409", error.code)
            assertEquals("Invite policy rejected this request", error.serverMessage)
        }
    }

    @Test
    fun memberRoleMutationPreservesItsExactServerErrorText() = runBlocking {
        val result =
            RealSettingsRepository(
                sampleStore(),
                FakeSettingsNetworkClient(
                    responseCode = 409,
                    errorJson = """{"error":"The last owner cannot be demoted","message":"must not be guessed"}"""
                )
            ).updateServerMemberRole(
                SettingsServerMemberRoleUpdateRequest(memberId = "member-2", role = "admin")
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("http_409", error.code)
        assertEquals("The last owner cannot be demoted", error.serverMessage)
    }

    @Test
    fun serverDeleteRequiresSlugConfirmationBeforeNetworkDelete() = runBlocking {
        val rejectedNetwork = FakeSettingsNetworkClient()
        val rejected =
            RealSettingsRepository(sampleStore(), rejectedNetwork)
                .deleteServer(SettingsServerDeleteRequest("Slock Android"))
        val acceptedNetwork = FakeSettingsNetworkClient()
        val accepted =
            RealSettingsRepository(sampleStore(), acceptedNetwork)
                .deleteServer(SettingsServerDeleteRequest("slock-android"))

        assertTrue(rejected is DataResult.Error)
        assertEquals("server_delete_confirmation_mismatch", rejected.error.code)
        assertEquals(null, rejectedNetwork.deletedServerId)
        assertTrue(accepted is DataResult.Content)
        assertEquals(true, accepted.data.success)
        assertEquals("server-1", acceptedNetwork.deletedServerId)
    }

    @Test
    fun computerDetailLoadsAgentsAndScansWorkspacesOnDemand() = runBlocking {
        val network =
            FakeSettingsNetworkClient(
                machinesJson = """
                    {"machines":[{"id":"machine-1","name":"MacBook","hostname":"mac.local","os":"macOS","description":"Main build computer","daemonVersion":"1.4.2","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"lastHeartbeat":"2026-05-07T01:00:00Z","createdAt":"2026-04-01T00:00:00Z"}]}
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
        assertNull(network.workspacesMachineId)
        assertEquals("MacBook", detail.computer.name)
        assertEquals("Main build computer", detail.computer.description)
        assertEquals("macOS", detail.computer.platform)
        // #74: server read-model fields must propagate to SettingsComputerSnapshot
        // so the React-aligned detail page can render Detected runtimes / Created / etc.
        assertEquals(7L, detail.computer.statusVersion)
        assertEquals("sk_machine_abc", detail.computer.apiKeyPrefix)
        assertEquals(listOf("claude", "codex"), detail.computer.runtimes)
        assertEquals("2026-04-01T00:00:00Z", detail.computer.createdAt)
        assertEquals("2026-05-07T01:00:00Z", detail.computer.lastHeartbeat)
        assertTrue(detail.workspaces.isEmpty())
        assertEquals(false, detail.workspacesScanned)
        assertEquals(1, detail.agents.size)
        assertEquals("Settings Integrator", detail.agents.first().name)
        assertEquals("Settings real-data wiring", detail.agents.first().memberProfile?.description)
        assertEquals("settings-integrator", detail.agents.first().memberProfile?.handle)
        assertTrue(detail.sections.any { it.key == "machine_status_events" })

        val workspaces = repository.scanComputerWorkspaces("machine-1")

        assertTrue(workspaces is DataResult.Content)
        assertEquals("machine-1", network.workspacesMachineId)
        assertEquals(1, workspaces.data.size)
        assertEquals("Slock", workspaces.data.first().name)
        assertEquals("~/.slock/agents/Slock/", workspaces.data.first().path)
        assertEquals(4096L, workspaces.data.first().totalSizeBytes)
        assertEquals(12, workspaces.data.first().fileCount)
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
        assertNull(detail.workspacesError)
        assertEquals(false, detail.workspacesScanned)
        assertEquals("server_forbidden", detail.agentsError?.code)
        assertEquals(false, detail.agentsError?.retryable)
        val workspaces = repository.scanComputerWorkspaces("machine-1")
        assertTrue(workspaces is DataResult.Error)
        assertEquals("machine_offline", workspaces.error.code)
        assertEquals(true, workspaces.error.retryable)
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
        val description = repository.updateComputerDescription(SettingsComputerDescriptionUpdateRequest("machine-1", "Build farm runner"))
        val rotate = repository.rotateComputerKey("machine-1")
        val runtimeModels = repository.getComputerRuntimeModels("machine-1", "claude")
        val deleteWorkspace = repository.deleteComputerWorkspace(SettingsComputerWorkspaceDeleteRequest("machine-1", "agent-old"))
        val deleteComputer = repository.deleteComputer("machine-1")

        assertTrue(register is DataResult.Content)
        assertEquals("machine-9", register.data.machine.id)
        assertEquals("sk_machine_a", register.data.apiKeyPrefix)
        assertTrue(rename is DataResult.Content)
        assertEquals("Builder Mac 2", rename.data.name)
        assertTrue(description is DataResult.Content)
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
        assertEquals("""{"name":"Builder Mac 2"}""", network.renameBodies.first())
        assertEquals("""{"description":"Build farm runner"}""", network.renameBodies.last())
        assertEquals("server-1", network.runtimeModelsServerId)
        assertEquals("machine-1", network.runtimeModelsMachineId)
        assertEquals("claude", network.runtimeModelsRuntime)
        assertEquals("agent-old", network.deletedWorkspaceDirectoryName)
        assertEquals("machine-1", network.deletedMachineId)
    }

    private class FakeSettingsNetworkClient(
        private val accountJson: String = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User"}""",
        private val authProvidersJson: String = """{"providers":[]}""",
        private val authIdentitiesJson: String = """{"identities":[]}""",
        private val providerLinkStartJson: String = """{"url":"https://raft.build/auth/google/link"}""",
        private val connectedAppClientsJson: String = """[]""",
        private val connectedAppBuiltInsJson: String = """[]""",
        private val connectedAppMarketplaceJson: String = """[]""",
        private val connectedAppCreateJson: String =
            """{"client":{"id":"client-created","serverId":"server-1","clientId":"client-created","name":"Created","appType":"server_local","publishStatus":"draft","category":"productivity"},"clientSecret":"secret_once"}""",
        private val connectedAppUpdateJson: String =
            """{"id":"client-1","serverId":"server-1","clientId":"client-1","name":"Updated","appType":"server_local","publishStatus":"draft","category":"productivity"}""",
        private val connectedAppDeleteJson: String =
            """{"id":"client-1","serverId":"server-1","clientId":"client-1","name":"Deleted","appType":"server_local","publishStatus":"draft","category":"productivity"}""",
        private val connectedAppPublishJson: String =
            """{"id":"client-1","serverId":"server-1","clientId":"client-1","name":"Published","appType":"server_local","publishStatus":"pending_review","category":"productivity"}""",
        private val connectedAppInstallJson: String =
            """{"id":"market-1","serverId":"server-1","clientId":"market-1","name":"Installed","appType":"marketplace","publishStatus":"approved","category":"productivity"}""",
        private val connectedAppUninstallJson: String = """{"clientId":"market-1","revokedGrantCount":1,"revokedTokenCount":1,"deniedPendingRequestCount":0}""",
        private val connectedAppLogoUploadJson: String =
            """{"id":"client-1","serverId":"server-1","clientId":"client-1","name":"Logo Client","appType":"server_local","publishStatus":"draft","category":"productivity","logoUrl":"https://cdn.example/logo.png"}""",
        private val connectedAppLogoClearJson: String =
            """{"id":"client-1","serverId":"server-1","clientId":"client-1","name":"Logo Client","appType":"server_local","publishStatus":"draft","category":"productivity","logoUrl":null}""",
        private val membersJson: String = """[]""",
        private val usageJson: String = """{"machines":0,"agents":0,"channels":0}""",
        private val billingSubscriptionJson: String = """{"subscription":null,"stripeConfigured":false}""",
        private val agreementJson: String = """{"enabled":false,"agreement":null}""",
        private val agreementUpdateJson: String = agreementJson,
        private val onboardingJson: String = """{"setupModalReminderOptOut":false}""",
        private val onboardingUpdateJson: String = onboardingJson,
        private val translationJson: String = """{"translationEnabled":false,"translationAvailable":false,"canManageTranslation":false}""",
        private val translationUpdateJson: String = translationJson,
        private val sidebarOrderJson: String = """{"channelOrder":[],"agentOrder":[],"dmOrder":[],"channelSortMode":"manual","dmSortMode":"manual","pinnedSortMode":"manual","pinnedChannelIds":[],"pinnedAgentIds":[],"pinnedOrder":[],"hiddenDmIds":[],"channelPanelTabOrder":[],"agentPanelTabOrder":[]}""",
        private val sidebarOrderUpdateJson: String = sidebarOrderJson,
        private val serverOrderJson: String = """{"serverOrder":[]}""",
        private val serverOrderUpdateJson: String = serverOrderJson,
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
        private val runtimeModelsJson: String =
            """{"models":[{"id":"sonnet","label":"Claude Sonnet"},{"id":"opus","label":"Claude Opus"}],"default":"sonnet"}""",
        private val commandJson: String = """{"ok":true}""",
        private val memberRoleUpdateJson: String = commandJson,
        private val removeMemberJson: String = commandJson,
        private val leaveServerJson: String = commandJson,
        private val machinesResponseCode: Int? = null,
        private val workspacesResponseCode: Int? = null,
        private val agentsResponseCode: Int? = null,
        private val responseCode: Int = 200,
        private val errorJson: String? = """{"error":"failed"}"""
    ) : RaftNetworkClient {
        private var currentConnectedAppClientsJson = connectedAppClientsJson
        private var currentServerPushMode =
            when {
                notificationSettingsJson.contains("mentions") -> "mentions"
                notificationSettingsJson.contains("true") -> "none"
                else -> "all"
            }
        private var currentPushPreviewMode =
            if (notificationSettingsJson.contains("\"private\"")) "private" else "full"
        private val canonicalNotificationModeSupported = notificationSettingsJson.contains("serverPushMode")
        private val pushPreviewModeSupported = notificationSettingsJson.contains("pushPreviewMode")
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
        var deletedServerId: String? = null
            private set
        var deletedWorkspaceDirectoryName: String? = null
            private set
        var updatedMemberRoleMemberId: String? = null
            private set
        var leaveServerCallCount: Int = 0
            private set
        val accountUpdateBodies = mutableListOf<String>()
        val connectedAppCreateBodies = mutableListOf<String>()
        val connectedAppUpdateBodies = mutableListOf<String>()
        val connectedAppPublishBodies = mutableListOf<String>()
        val uploadedConnectedAppLogoIds = mutableListOf<String>()
        val connectedAppLogoFieldNames = mutableListOf<String>()
        val connectedAppLogoFileNames = mutableListOf<String>()
        var clearedConnectedAppLogoId: String? = null
            private set
        var deletedConnectedAppId: String? = null
            private set
        var installedMarketplaceAppId: String? = null
            private set
        var uninstalledMarketplaceAppId: String? = null
            private set
        val inviteBodies = mutableListOf<String>()
        val joinLinkBodies = mutableListOf<String>()
        val agreementBodies = mutableListOf<String>()
        val notificationSettingsBodies = mutableListOf<String>()
        val onboardingBodies = mutableListOf<String>()
        val profileBodies = mutableListOf<String>()
        val translationBodies = mutableListOf<String>()
        val sidebarBodies = mutableListOf<String>()
        val serverOrderBodies = mutableListOf<String>()
        val memberRoleBodies = mutableListOf<String>()
        val registerBodies = mutableListOf<String>()
        val renameBodies = mutableListOf<String>()
        val avatarFileNames = mutableListOf<String>()
        val providerLinkProviderIds = mutableListOf<String>()
        val providerLinkBodies = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: build.raft.app.model.ServerId?,
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
                normalized == "auth/providers" && method == "GET" ->
                    response(authProvidersJson)
                normalized == "auth/identities" && method == "GET" ->
                    response(authIdentitiesJson)
                normalized.matches(Regex("""auth/[^/]+/link/start""")) && method == "POST" -> {
                    providerLinkProviderIds += normalized.split("/")[1]
                    providerLinkBodies += bodyText.orEmpty()
                    response(providerLinkStartJson)
                }
                normalized == "integrations/clients" && method == "GET" ->
                    response(currentConnectedAppClientsJson)
                normalized == "integrations/clients" && method == "POST" -> {
                    connectedAppCreateBodies += bodyText.orEmpty()
                    response(connectedAppCreateJson)
                }
                normalized.matches(Regex("""integrations/clients/[^/]+$""")) && method == "PATCH" -> {
                    connectedAppUpdateBodies += bodyText.orEmpty()
                    response(connectedAppUpdateJson)
                }
                normalized.matches(Regex("""integrations/clients/[^/]+$""")) && method == "DELETE" -> {
                    deletedConnectedAppId = normalized.split("/")[2]
                    response(connectedAppDeleteJson)
                }
                normalized.matches(Regex("""integrations/clients/[^/]+/request-publish""")) && method == "POST" -> {
                    connectedAppPublishBodies += bodyText.orEmpty()
                    response(connectedAppPublishJson)
                }
                normalized.matches(Regex("""integrations/clients/[^/]+/logo""")) && method == "DELETE" -> {
                    clearedConnectedAppLogoId = normalized.split("/")[2]
                    currentConnectedAppClientsJson = "[$connectedAppLogoClearJson]"
                    response(connectedAppLogoClearJson)
                }
                normalized == "integrations/built-in" && method == "GET" ->
                    response(connectedAppBuiltInsJson)
                normalized == "integrations/marketplace" && method == "GET" ->
                    response(connectedAppMarketplaceJson)
                normalized.matches(Regex("""integrations/marketplace/[^/]+/install""")) && method == "POST" -> {
                    installedMarketplaceAppId = normalized.split("/")[2]
                    response(connectedAppInstallJson)
                }
                normalized.matches(Regex("""integrations/marketplace/[^/]+/install""")) && method == "DELETE" -> {
                    uninstalledMarketplaceAppId = normalized.split("/")[2]
                    response(connectedAppUninstallJson)
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
                normalized.matches(Regex("""servers/[^/]+/agreement""")) && method == "GET" ->
                    response(agreementJson)
                normalized.matches(Regex("""servers/[^/]+/agreement""")) && method == "PUT" -> {
                    agreementBodies += bodyText.orEmpty()
                    response(agreementUpdateJson)
                }
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
                normalized == "servers/order" && method == "GET" ->
                    response(serverOrderJson)
                normalized == "servers/order" && method == "PATCH" -> {
                    serverOrderBodies += bodyText.orEmpty()
                    response(serverOrderUpdateJson)
                }
                normalized.matches(Regex("""servers/[^/]+/notification-settings""")) && method == "GET" ->
                    response(if (canonicalNotificationModeSupported) {
                        """{"serverPushMuted":${currentServerPushMode == "none"},"serverPushMentionsOnly":${currentServerPushMode == "mentions"},"serverPushMode":"$currentServerPushMode"${if (pushPreviewModeSupported) ",\"pushPreviewMode\":\"$currentPushPreviewMode\"" else ""}}"""
                    } else {
                        """{"serverPushMuted":${currentServerPushMode == "none"}}"""
                    })
                normalized.matches(Regex("""servers/[^/]+/notification-settings""")) && method == "PATCH" -> {
                    notificationSettingsBodies += bodyText.orEmpty()
                    if (bodyText?.contains("serverPushMode") == true || bodyText?.contains("serverPushMuted") == true) {
                        currentServerPushMode =
                            when {
                                bodyText.contains("\"mentions\"") -> "mentions"
                                bodyText.contains("\"none\"") || bodyText.contains("true") -> "none"
                                else -> "all"
                            }
                    }
                    if (bodyText?.contains("pushPreviewMode") == true) {
                        currentPushPreviewMode = if (bodyText.contains("\"private\"")) "private" else "full"
                    }
                    response(if (canonicalNotificationModeSupported) {
                        """{"serverPushMuted":${currentServerPushMode == "none"},"serverPushMentionsOnly":${currentServerPushMode == "mentions"},"serverPushMode":"$currentServerPushMode"${if (pushPreviewModeSupported) ",\"pushPreviewMode\":\"$currentPushPreviewMode\"" else ""}}"""
                    } else {
                        """{"serverPushMuted":${currentServerPushMode == "none"}}"""
                    })
                }
                normalized.matches(Regex("""servers/[^/]+""")) && method == "PATCH" -> {
                    val serverId = normalized.split("/")[1]
                    profileBodies += bodyText.orEmpty()
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
                normalized.matches(Regex("""servers/[^/]+""")) && method == "DELETE" -> {
                    deletedServerId = normalized.split("/")[1]
                    response("""{"ok":true}""")
                }
                else -> error("Unhandled request $method $normalized")
            }
        }

        private fun response(json: String, code: Int = responseCode): HomeNetworkResponse =
            if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = json)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = errorJson)
            }

        override suspend fun uploadAccountAvatar(
            priority: DataRequestPriority,
            file: MultipartFile
        ): HomeNetworkResponse {
            avatarFileNames += file.name
            return response(accountJson)
        }

        override suspend fun uploadConnectedAppLogo(
            priority: DataRequestPriority,
            clientRecordId: String,
            file: MultipartFile
        ): HomeNetworkResponse {
            uploadedConnectedAppLogoIds += clientRecordId
            connectedAppLogoFieldNames += file.name
            connectedAppLogoFileNames += file.fileName
            currentConnectedAppClientsJson = "[$connectedAppLogoUploadJson]"
            return response(connectedAppLogoUploadJson)
        }
    }

    private fun sampleStore(session: StoredAuthSession = sampleSession()): RaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(session)

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
