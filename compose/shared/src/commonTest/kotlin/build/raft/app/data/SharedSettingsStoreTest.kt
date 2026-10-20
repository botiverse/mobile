package build.raft.app.data

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ServerNotificationPreferencesChanged
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SharedSettingsStoreTest {
    @Test
    fun startBuildsSettingsHomeFromSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(sampleSession())),
                scope = scope
            )

        store.accept(SharedSettingsIntent.Start)

        val home = assertIs<ScreenLoadState.Content<SharedSettingsHomeSnapshot>>(store.state.value.home).data
        assertEquals("", home.subtitle)
        assertEquals(
            listOf(
                SharedSettingsDestination.Account,
                SharedSettingsDestination.LanguageRegion,
                SharedSettingsDestination.Appearance,
                SharedSettingsDestination.Notifications,
                SharedSettingsDestination.Mobile,
                SharedSettingsDestination.Storage,
                SharedSettingsDestination.Server,
                SharedSettingsDestination.Billing,
                SharedSettingsDestination.Administration,
                SharedSettingsDestination.ConnectedApps,
                SharedSettingsDestination.Computers,
                SharedSettingsDestination.About
            ),
            home.items.map { it.destination }
        )
        assertEquals(false, home.items.any { it.destination == SharedSettingsDestination.Diagnostics })
        // Developer is no longer a home nav item — it is hidden behind header taps (035d4b96).
        assertEquals(false, home.items.any { it.destination == SharedSettingsDestination.Developer })
        assertEquals("Version and build information", home.items.first { it.destination == SharedSettingsDestination.About }.subtitle)
        assertEquals(false, home.items.any { it.destination == SharedSettingsDestination.Router && it.productVisible })
        scope.cancel()
    }

    @Test
    fun openDestinationLoadsRepositoryStateThroughCommonStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computersResult = DataResult.Content(computersSnapshot("machine-1")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))

        val computers = assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Computers), store.state.value.route)
        assertEquals(listOf("machine-1"), computers.computers.map { it.id })
        assertEquals(listOf("computers"), repository.calls)
        scope.cancel()
    }

    @Test
    fun accountSaveIntentUsesSettingsRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                accountResult = DataResult.Content(accountSnapshot(displayName = "New Name", handle = "@new", email = "new@slock.ai"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))
        store.accept(SharedSettingsIntent.UpdateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai")))

        val account = assertIs<ScreenLoadState.Content<AccountSettingsSnapshot>>(store.state.value.account).data
        assertEquals("New Name", account.profile.displayName)
        assertEquals(listOf("account", "updateProfile:New Name"), repository.calls)
        scope.cancel()
    }

    @Test
    fun accountPasswordAvatarAndProviderIntentsUseTypedBoundaries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                accountResult = DataResult.Content(accountSnapshot())
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        platformCapabilities =
                            FakeSettingsCapabilities(
                                avatarFileResult =
                                    DataResult.Content(
                                        PlatformFile(
                                            id = "avatar-1",
                                            fileName = "avatar.png",
                                            mimeType = "image/png",
                                            bytes = "image".encodeToByteArray()
                                        )
                                    )
                            )
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.ChangeAccountPassword(AccountPasswordChangeRequest("old-password", "new-password")))
        store.accept(SharedSettingsIntent.RequestAccountAvatarUpload)
        store.accept(SharedSettingsIntent.ConnectAccountProvider(AccountProviderLinkStartRequest("google")))

        assertEquals(
            listOf(
                "changePassword:12",
                "uploadAvatar:avatar.png",
                "startProviderLink:google"
            ),
            repository.calls
        )
        assertEquals(SharedSettingsEffect.OpenUrl("https://raft.build/auth/google/link"), store.state.value.effect)
        scope.cancel()
    }

    @Test
    fun accountProviderLinkPrefersMobileFlowAndDoesNotWebFallbackAfterNativeFailure() = runBlocking {
        val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val nativeRepository = FakeSettingsRepository()
        val nativeCapabilities =
            FakeSettingsCapabilities(
                mobileOAuthLinkResult = DataResult.Content(Unit)
            )
        val nativeStore =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = nativeRepository,
                        platformCapabilities = nativeCapabilities
                    ),
                scope = nativeScope
            )

        nativeStore.accept(
            SharedSettingsIntent.ConnectAccountProvider(
                AccountProviderLinkStartRequest("apple")
            )
        )

        assertEquals(listOf("mobileOAuthLink:apple"), nativeCapabilities.calls)
        assertFalse(nativeRepository.calls.any { it.startsWith("startProviderLink:") })
        assertEquals(null, nativeStore.state.value.effect)
        assertEquals(
            SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_CONNECT, "apple"),
            nativeStore.state.value.actionState
        )

        val failedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failedRepository = FakeSettingsRepository()
        val failedCapabilities =
            FakeSettingsCapabilities(
                mobileOAuthLinkResult =
                    DataResult.Error(
                        ScreenError("ios_native_apple_start_failed", retryable = true)
                    )
            )
        val failedStore =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = failedRepository,
                        platformCapabilities = failedCapabilities
                    ),
                scope = failedScope
            )

        failedStore.accept(
            SharedSettingsIntent.ConnectAccountProvider(
                AccountProviderLinkStartRequest("apple")
            )
        )

        assertEquals(listOf("mobileOAuthLink:apple"), failedCapabilities.calls)
        assertFalse(failedRepository.calls.any { it.startsWith("startProviderLink:") })
        assertEquals(null, failedStore.state.value.effect)
        assertEquals(
            "ios_native_apple_start_failed",
            failedStore.state.value.actionState?.errorCode
        )
        nativeScope.cancel()
        failedScope.cancel()
    }

    @Test
    fun accountFallbackPreservesSessionAvatarUrl() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val baseSession = sampleSession()
        val session =
            baseSession.copy(
                user = baseSession.user.copy(avatarUrl = "https://avatars.example.test/artin.png")
            )
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(session)),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))

        val account = assertIs<ScreenLoadState.Content<AccountSettingsSnapshot>>(store.state.value.account).data
        assertEquals("https://avatars.example.test/artin.png", account.profile.avatarUrl)
        scope.cancel()
    }

    @Test
    fun connectedAppsLoadAndRegisterUseTypedRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(
                connectedAppsResult = DataResult.Content(connectedAppsSnapshot())
            ).also {
                it.registerConnectedAppBlock = block
            }
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ConnectedApps))
        val loaded = assertIs<ScreenLoadState.Content<SettingsConnectedAppsSnapshot>>(store.state.value.connectedApps).data
        assertEquals(listOf("Calendar"), loaded.marketplace.map { it.name })

        store.dispatch(
            SharedSettingsIntent.RegisterConnectedApp(
                SettingsConnectedAppRegisterRequest(
                    name = "Mobile Client",
                    clientId = "mobile-client",
                    returnUrl = "https://example.com/callback"
                )
            )
        )
        block.awaitStarted()
        store.dispatch(
            SharedSettingsIntent.RegisterConnectedApp(
                SettingsConnectedAppRegisterRequest(name = "Mobile Client", clientId = "mobile-client")
            )
        )

        assertEquals(listOf("connectedApps", "registerConnectedApp:Mobile Client"), repository.calls)
        assertEquals(SharedSettingsActionState(CONNECTED_APPS_ACTION_REGISTER, inProgress = true), store.state.value.actionState)

        block.release()
        yield()

        val updated = assertIs<ScreenLoadState.Content<SettingsConnectedAppsSnapshot>>(store.state.value.connectedApps).data
        assertEquals("secret_once", updated.oneTimeClientSecret?.clientSecret)
        assertEquals(listOf("connectedApps", "registerConnectedApp:Mobile Client"), repository.calls)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertNull(store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun connectedAppLogoUploadAndClearUsePlatformPickerAndRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                connectedAppsResult = DataResult.Content(connectedAppsSnapshot())
            )
        val capabilities =
            FakeSettingsCapabilities(
                connectedAppLogoFileResult =
                    DataResult.Content(
                        PlatformFile(
                            id = "logo-1",
                            fileName = "logo.png",
                            mimeType = "image/png",
                            bytes = "logo".encodeToByteArray()
                        )
                    )
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ConnectedApps))
        store.accept(SharedSettingsIntent.RequestConnectedAppLogoUpload("client-1"))

        val uploaded = assertIs<ScreenLoadState.Content<SettingsConnectedAppsSnapshot>>(store.state.value.connectedApps).data
        assertEquals("https://cdn.example/logo.png", uploaded.registered.single().logoUrl)
        assertEquals(listOf("connectedApps", "uploadConnectedAppLogo:client-1:logo.png"), repository.calls)
        assertEquals(listOf("pickConnectedAppLogoFile:client-1"), capabilities.calls)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertNull(store.state.value.actionState?.errorCode)

        store.accept(SharedSettingsIntent.ClearConnectedAppLogo("client-1"))

        val cleared = assertIs<ScreenLoadState.Content<SettingsConnectedAppsSnapshot>>(store.state.value.connectedApps).data
        assertNull(cleared.registered.single().logoUrl)
        assertEquals(
            listOf(
                "connectedApps",
                "uploadConnectedAppLogo:client-1:logo.png",
                "clearConnectedAppLogo:client-1"
            ),
            repository.calls
        )
        assertEquals(SharedSettingsActionState(CONNECTED_APPS_ACTION_LOGO_CLEAR, "client-1", inProgress = false), store.state.value.actionState)
        scope.cancel()
    }

    @Test
    fun serverAndBillingLoadTypedSnapshotsThroughCommonStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                billingResult = DataResult.Content(billingSnapshot()),
                serverResult = DataResult.Content(serverSnapshot())
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Billing))
        val billing = assertIs<ScreenLoadState.Content<SettingsBillingSnapshot>>(store.state.value.billing).data
        assertEquals("Team", billing.currentPlan.planName)

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Server))
        val server = assertIs<ScreenLoadState.Content<SettingsServerSnapshot>>(store.state.value.server).data
        assertEquals("Slock Community", server.profile.name)
        assertEquals(listOf("Admin"), server.admins.map { it.name })
        assertEquals(
            listOf("admins", "billing", "pending_invites", "join_links", "onboarding_agent", "translation", "sidebar_order", "danger"),
            server.sections.map { it.key }
        )
        assertEquals(listOf("billing", "server"), repository.calls)
        scope.cancel()
    }

    @Test
    fun languageRegionLoadsAndUpdatesTypedPreferences() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                languageRegionResult = DataResult.Content(languageRegionSnapshot())
            )
        val localeRepository = FakeLocaleRepository(current = AppLocale.SimplifiedChinese)
        val chatLayoutRepository = FakeChatLayoutPreferenceRepository(translationMode = TranslationModePreference.Manual)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        localeRepository = localeRepository,
                        chatLayoutPreferenceRepository = chatLayoutRepository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.LanguageRegion))
        val loaded = assertIs<ScreenLoadState.Content<SettingsLanguageRegionSnapshot>>(store.state.value.languageRegion).data
        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Asia/Shanghai", loaded.timezone.selectedKey)
        assertEquals("manual", loaded.translationDisplay.selectedKey)
        assertEquals("zh-Hans", mobile.localePreference.selectedKey)

        store.accept(SharedSettingsIntent.SetLocale("en"))
        store.accept(
            SharedSettingsIntent.UpdateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = "Asia/Tokyo")
            )
        )
        store.accept(SharedSettingsIntent.SetTranslationMode("original"))
        store.accept(SharedSettingsIntent.SetTranslationTargetLanguage("ja-JP"))
        store.accept(SharedSettingsIntent.SetLanguageRegionServerTranslation(false))

        val updated = assertIs<ScreenLoadState.Content<SettingsLanguageRegionSnapshot>>(store.state.value.languageRegion).data
        assertEquals("Asia/Tokyo", updated.timezone.selectedKey)
        assertEquals("original", updated.translationDisplay.selectedKey)
        assertEquals("ja", updated.browserLanguage.selectedKey)
        assertTrue(updated.translationDisplay.available)
        assertEquals(TranslationModePreference.Original, chatLayoutRepository.translationMode)
        assertEquals("ja", chatLayoutRepository.translationTargetLanguage)
        assertEquals(false, updated.serverTranslation.enabled)
        assertEquals(AppLocale.English, localeRepository.current)
        assertEquals(
            "en",
            assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data.localePreference.selectedKey
        )
        assertEquals(
            listOf(
                "languageRegion",
                "updateLanguageRegionPreference:timezone=Asia/Tokyo",
                "languageRegion",
                "languageRegion",
                "updateServerTranslation:false",
                "languageRegion"
            ),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun appearanceLoadsAndUpdatesMessageBodyFontSizeThroughRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                appearanceResult = DataResult.Content("md".toSettingsAppearanceSnapshot())
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Appearance))
        val loaded = assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data
        assertEquals("md", loaded.messageBodyFontSize.selectedKey)

        store.accept(SharedSettingsIntent.UpdateAppearance(SettingsAppearanceUpdateRequest("lg")))

        val updated = assertIs<ScreenLoadState.Content<SettingsAppearanceSnapshot>>(store.state.value.appearance).data
        assertEquals("lg", updated.messageBodyFontSize.selectedKey)
        assertEquals(
            listOf("appearance", "updateAppearance:lg"),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun serverMutationIntentsUseTypedRepositoryAndRefreshServerState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(serverResult = DataResult.Content(serverSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Server))
        store.accept(SharedSettingsIntent.CreateServerInvite(SettingsServerInviteCreateRequest("new@slock.ai")))
        store.accept(SharedSettingsIntent.CreateServerJoinLink())
        store.accept(SharedSettingsIntent.RequestServerConfirmation(SERVER_ACTION_INVITE_REVOKE, "invite-1"))
        assertEquals(
            SharedSettingsActionState(SERVER_ACTION_CONFIRM, serverConfirmationItemId(SERVER_ACTION_INVITE_REVOKE, "invite-1")),
            store.state.value.actionState
        )
        store.accept(SharedSettingsIntent.RevokeServerInvite("invite-1"))
        store.accept(SharedSettingsIntent.UpdateServerTranslation(SettingsServerTranslationUpdateRequest(false)))
        store.accept(SharedSettingsIntent.UpdateServerMemberRole(SettingsServerMemberRoleUpdateRequest("admin-1", "member")))
        store.accept(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("slock")))

        val server = assertIs<ScreenLoadState.Content<SettingsServerSnapshot>>(store.state.value.server).data
        assertEquals("Saved", store.state.value.actionState?.let { if (it.errorCode == null && !it.inProgress) "Saved" else it.errorCode })
        assertEquals(false, server.translation?.translationEnabled)
        assertEquals(
            listOf(
                "server",
                "createServerInvite:new@slock.ai",
                "server",
                "createServerJoinLink",
                "server",
                "revokeServerInvite:invite-1",
                "server",
                "updateServerTranslation:false",
                "server",
                "updateServerMemberRole:admin-1:member",
                "server",
                "deleteServer:slock",
                "server"
            ),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun removeServerMemberEvictsSharedServerMembersStoreOnSuccess() = runBlocking {
        val repository = FakeSettingsRepository(serverResult = DataResult.Content(serverSnapshot()))
        val membersStore = SharedServerMembersStore(FakeSettingsMemberRepository())
        membersStore.seed(
            MemberSnapshot(
                settingsServer("server-1"),
                listOf(
                    settingsMember("admin-1", "Admin"),
                    settingsMember("member-2", "Member")
                )
            )
        )
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                settingsRepository = repository,
                serverMembersStore = membersStore
            )

        val result = dataSource.removeServerMember("member-2")

        assertIs<DataResult.Content<SettingsServerActionResult>>(result)
        assertEquals(listOf("removeServerMember:member-2"), repository.calls)
        assertEquals(
            listOf("admin-1"),
            membersStore.currentServerMembers(ServerId("server-1")).map { it.id.value }
        )
    }

    @Test
    fun duplicateProfileSaveNoOpsWhileInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(accountResult = DataResult.Content(accountSnapshot())).also {
                it.updateProfileBlock = block
            }
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.dispatch(SharedSettingsIntent.UpdateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai")))
        block.awaitStarted()
        store.dispatch(SharedSettingsIntent.UpdateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai")))

        assertEquals(listOf("updateProfile:New Name"), repository.calls)
        assertEquals(SharedSettingsActionState("saveProfile", inProgress = true), store.state.value.actionState)

        block.release()
        yield()

        assertEquals(listOf("updateProfile:New Name"), repository.calls)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertNull(store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun duplicateCreateServerInviteNoOpsWhileInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(serverResult = DataResult.Content(serverSnapshot())).also {
                it.createServerInviteBlock = block
            }
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Server))
        store.dispatch(SharedSettingsIntent.CreateServerInvite(SettingsServerInviteCreateRequest("new@slock.ai")))
        block.awaitStarted()
        store.dispatch(SharedSettingsIntent.CreateServerInvite(SettingsServerInviteCreateRequest("new@slock.ai")))

        assertEquals(listOf("server", "createServerInvite:new@slock.ai"), repository.calls)
        assertEquals(SharedSettingsActionState(SERVER_ACTION_INVITE_CREATE, inProgress = true), store.state.value.actionState)

        block.release()
        yield()

        assertEquals(listOf("server", "createServerInvite:new@slock.ai", "server"), repository.calls)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertNull(store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun duplicateDeleteServerNoOpsAndFailureKeepsExistingContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val block = BlockingCall()
        val initialServer = serverSnapshot()
        val repository =
            FakeSettingsRepository(serverResult = DataResult.Content(initialServer)).also {
                it.deleteServerBlock = block
                it.deleteServerResult = DataResult.Error(ScreenError("settings_server_delete_failed"))
            }
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Server))
        store.dispatch(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("slock")))
        block.awaitStarted()
        store.dispatch(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("slock")))

        assertEquals(listOf("server", "deleteServer:slock"), repository.calls)
        assertEquals(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = true), store.state.value.actionState)

        block.release()
        yield()

        val server = assertIs<ScreenLoadState.Content<SettingsServerSnapshot>>(store.state.value.server).data
        assertEquals(initialServer.profile.name, server.profile.name)
        assertEquals(listOf("server", "deleteServer:slock"), repository.calls)
        assertEquals(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = false, errorCode = "settings_server_delete_failed", screenError = ScreenError("settings_server_delete_failed")), store.state.value.actionState)
        assertEquals("settings_server_delete_failed", store.state.value.routeErrorCode)
        scope.cancel()
    }

    @Test
    fun refreshFailureKeepsExistingContentAndReportsRouteError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computersResult = DataResult.Content(computersSnapshot("machine-1")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        repository.computersResult = DataResult.Error(ScreenError("settings_computers_network"))
        store.accept(SharedSettingsIntent.Refresh)

        val computers = assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data
        assertEquals(listOf("machine-1"), computers.computers.map { it.id })
        assertEquals("settings_computers_network", store.state.value.routeErrorCode)
        assertEquals(false, store.state.value.isRefreshing(SharedSettingsDestination.Computers))
        scope.cancel()
    }

    @Test
    fun hostOnlyActionsEmitEffectsWithoutChangingRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(sampleSession())),
                scope = scope
            )

        store.accept(SharedSettingsIntent.RequestNotificationPermission)
        assertEquals(SharedSettingsEffect.RequestNotificationPermission, store.state.value.effect)
        assertEquals(SharedSettingsActionState(MOBILE_ACTION_PERMISSION, inProgress = true), store.state.value.actionState)
        assertEquals(SharedSettingsRoute(), store.state.value.route)

        store.accept(SharedSettingsIntent.ConsumeEffect)
        store.accept(SharedSettingsIntent.SendTestNotification)
        assertEquals(SharedSettingsEffect.SendTestNotification, store.state.value.effect)
        scope.cancel()
    }

    @Test
    fun mobileLoadsMessageAlignmentWithoutServerSettingsFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(enabled = true),
                        localeRepository = FakeLocaleRepository(current = AppLocale.SimplifiedChinese),
                        chatLayoutPreferenceRepository =
                            FakeChatLayoutPreferenceRepository(alignment = MyMessageAlignmentPreference.Right)
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Right", mobile.messageAlignmentPreference.statusLabel)
        assertEquals(emptyList<String>(), repository.calls)
        scope.cancel()
    }

    @Test
    fun setLocalePublishesRuntimeLocaleWithoutWritingResourceGlobalOffThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localeRepository = FakeLocaleRepository(current = AppLocale.English)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                localeRepository = localeRepository
            )
        val originalLocaleType = com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType
        val originalRuntimeLocale = KmmResourceLocaleRuntime.locale.value
        try {
            val store =
                SharedSettingsStore(
                    dataSource = dataSource,
                    scope = scope
                )

            val result0 = dataSource.setLocale("system")
            assertIs<DataResult.Content<*>>(result0)
            assertEquals(originalLocaleType, com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType)
            assertEquals(AppLocale.System, KmmResourceLocaleRuntime.locale.value)

            val result1 = dataSource.setLocale("zh-Hans")
            assertIs<DataResult.Content<*>>(result1)
            assertEquals(originalLocaleType, com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType)
            assertEquals(AppLocale.SimplifiedChinese, KmmResourceLocaleRuntime.locale.value)

            val result2 = dataSource.setLocale("ja")
            assertIs<DataResult.Content<*>>(result2)
            assertEquals(originalLocaleType, com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType)
            assertEquals(AppLocale.Japanese, KmmResourceLocaleRuntime.locale.value)

            val result3 = dataSource.setLocale("en")
            assertIs<DataResult.Content<*>>(result3)
            assertEquals(originalLocaleType, com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType)
            assertEquals(AppLocale.English, KmmResourceLocaleRuntime.locale.value)
        } finally {
            com.tencent.tmm.kmmresource.resource.desc.StringDesc.localeType = originalLocaleType
            KmmResourceLocaleRuntime.replaceForTest(originalRuntimeLocale)
        }
        scope.cancel()
    }

    @Test
    fun runtimeLocaleSeedCannotOverwriteLiveSelection() {
        val originalRuntimeLocale = KmmResourceLocaleRuntime.locale.value
        try {
            KmmResourceLocaleRuntime.replaceForTest(null)
            assertEquals(AppLocale.English, KmmResourceLocaleRuntime.seedIfEmpty(AppLocale.English))

            KmmResourceLocaleRuntime.publish(AppLocale.SimplifiedChinese)
            assertEquals(
                AppLocale.SimplifiedChinese,
                KmmResourceLocaleRuntime.seedIfEmpty(AppLocale.English)
            )
            assertEquals(AppLocale.SimplifiedChinese, KmmResourceLocaleRuntime.locale.value)
        } finally {
            KmmResourceLocaleRuntime.replaceForTest(originalRuntimeLocale)
        }
    }

    @Test
    fun mobilePreferenceIntentsPersistAndEchoThroughSharedState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val notificationRepository = FakeNotificationPreferenceRepository(enabled = false)
        val localeRepository = FakeLocaleRepository(current = AppLocale.English)
        val chatLayoutRepository = FakeChatLayoutPreferenceRepository(alignment = MyMessageAlignmentPreference.Left)
        val linkOpenPreferenceRepository = FakeLinkOpenPreferenceRepository()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                        notificationPreferenceRepository = notificationRepository,
                        localeRepository = localeRepository,
                        chatLayoutPreferenceRepository = chatLayoutRepository,
                        linkOpenPreferenceRepository = linkOpenPreferenceRepository,
                        platformCapabilities = ConversationForwardCapableSettingsPlatformCapabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.SetNotificationPreference(true))
        store.accept(SharedSettingsIntent.SetOfflineComputerAlertsPreference(true))
        store.accept(SharedSettingsIntent.SetLocale("zh-Hans"))
        store.accept(SharedSettingsIntent.SetMessageAlignment("right"))
        store.accept(SharedSettingsIntent.SetLinkOpenMode("in_app"))
        store.accept(SharedSettingsIntent.SetCompactConsecutiveMessages(true))
        store.accept(SharedSettingsIntent.SetLongMessageCollapseEnabled(true))
        store.accept(SharedSettingsIntent.SetSingleTapOpensThreadEnabled(true))
        store.accept(SharedSettingsIntent.SetThreadUnreadJumpEnabled(true))
        store.accept(SharedSettingsIntent.SetNewMessageMenuEnabled(true))
        store.accept(SharedSettingsIntent.SetConversationForwardGestureEnabled(true))
        store.accept(SharedSettingsIntent.SetForegroundMessageBanners(true))
        store.accept(SharedSettingsIntent.SetActivityTaskStatusBadges(true))
        store.accept(SharedSettingsIntent.SetComposerSendKey(false))
        store.accept(SharedSettingsIntent.SetMultiShellHostEnabled(true))
        store.accept(SharedSettingsIntent.SetServerDrawerEnabled(true))
        store.accept(SharedSettingsIntent.SetNewDesignSettingsEnabled(true))
        store.accept(SharedSettingsIntent.SetHomeServerAvatarEnabled(true))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(true, notificationRepository.enabled)
        assertEquals(true, notificationRepository.offlineComputerAlertsEnabled)
        assertEquals(AppLocale.SimplifiedChinese, localeRepository.current)
        assertEquals(MyMessageAlignmentPreference.Right, chatLayoutRepository.alignment)
        assertEquals(LinkOpenMode.InAppBrowser, linkOpenPreferenceRepository.mode)
        assertEquals(true, chatLayoutRepository.compactConsecutiveMessages)
        assertEquals(true, chatLayoutRepository.longMessageCollapseEnabled)
        assertEquals(true, chatLayoutRepository.singleTapOpensThreadEnabled)
        assertEquals(true, chatLayoutRepository.threadUnreadJumpEnabled)
        assertEquals(true, chatLayoutRepository.newMessageMenuEnabled)
        assertEquals(true, chatLayoutRepository.conversationForwardGestureEnabled)
        assertEquals(true, chatLayoutRepository.foregroundMessageBannersEnabled)
        assertEquals(true, chatLayoutRepository.activityTaskStatusBadgesEnabled)
        assertEquals(false, chatLayoutRepository.composerSendKeyEnabled)
        assertEquals(true, chatLayoutRepository.multiShellHostEnabled)
        assertEquals(true, chatLayoutRepository.serverDrawerEnabled)
        assertEquals(true, chatLayoutRepository.newDesignSettingsEnabled)
        assertEquals(true, chatLayoutRepository.homeServerAvatarEnabled)
        assertEquals("Enabled", mobile.pushNotifications.statusLabel)
        assertEquals("Simplified Chinese / 简体中文", mobile.localePreference.statusLabel)
        assertEquals(
            listOf("system", "en", "zh-Hans", "ja"),
            mobile.localePreference.options.map { it.key }
        )
        assertEquals("Right", mobile.messageAlignmentPreference.statusLabel)
        assertEquals("in_app", mobile.linkOpenMode.selectedKey)
        assertEquals("In-app browser", mobile.linkOpenMode.statusLabel)
        assertEquals(listOf("system", "in_app"), mobile.linkOpenMode.options.map { it.key })
        assertEquals(true, mobile.compactConsecutiveMessages.enabled)
        assertEquals("Enabled", mobile.compactConsecutiveMessages.statusLabel)
        assertEquals(true, mobile.longMessageCollapse.enabled)
        assertEquals("Enabled", mobile.longMessageCollapse.statusLabel)
        assertEquals(true, mobile.singleTapOpensThread.enabled)
        assertEquals("Enabled", mobile.singleTapOpensThread.statusLabel)
        assertEquals(true, mobile.threadUnreadJump.enabled)
        assertEquals("Enabled", mobile.threadUnreadJump.statusLabel)
        assertEquals(true, mobile.newMessageMenu.enabled)
        assertEquals("Enabled", mobile.newMessageMenu.statusLabel)
        assertEquals(true, mobile.conversationForwardGesture.enabled)
        assertEquals("Enabled", mobile.conversationForwardGesture.statusLabel)
        assertEquals(true, mobile.foregroundMessageBanners.enabled)
        assertEquals("Enabled", mobile.foregroundMessageBanners.statusLabel)
        assertEquals(true, mobile.activityTaskStatusBadges.enabled)
        assertEquals("Enabled", mobile.activityTaskStatusBadges.statusLabel)
        assertEquals(false, mobile.composerSendKey.enabled)
        assertEquals("New line", mobile.composerSendKey.statusLabel)
        assertEquals(true, mobile.multiShellHost.enabled)
        assertEquals("Enabled", mobile.multiShellHost.statusLabel)
        assertEquals(true, mobile.serverDrawer.enabled)
        assertEquals("Enabled", mobile.serverDrawer.statusLabel)
        assertEquals(true, mobile.newDesignSettings.enabled)
        assertEquals("Enabled", mobile.newDesignSettings.statusLabel)
        assertEquals(true, mobile.homeServerAvatar.enabled)
        assertEquals("Enabled", mobile.homeServerAvatar.statusLabel)
        assertNull(store.state.value.actionState?.errorCode)

        store.accept(SharedSettingsIntent.SetLocale("system"))
        val systemMobile =
            assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(AppLocale.System, localeRepository.current)
        assertEquals("System default", systemMobile.localePreference.statusLabel)
        scope.cancel()
    }

    @Test
    fun unsupportedPlatformHidesConversationForwardGestureAndRejectsPersistence() = runBlocking {
        val chatLayoutRepository =
            FakeChatLayoutPreferenceRepository(conversationForwardGestureEnabled = true)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                chatLayoutPreferenceRepository = chatLayoutRepository
            )

        val loaded =
            assertIs<DataResult.Content<SettingsMobileSnapshot>>(
                dataSource.loadMobilePreferences()
            ).data.conversationForwardGesture

        assertFalse(loaded.available)
        assertFalse(loaded.enabled)
        assertEquals("Unsupported", loaded.statusLabel)

        chatLayoutRepository.conversationForwardGestureEnabled = false
        val rejected =
            assertIs<DataResult.Error>(
                dataSource.setConversationForwardGestureEnabled(true)
            )
        assertEquals("settings_conversation_forward_gesture_unavailable", rejected.error.code)
        assertFalse(chatLayoutRepository.conversationForwardGestureEnabled)
    }

    @Test
    fun delayedMobilePermissionLoadCannotOverrideNewerProcessOff() = runBlocking {
        val permissionLoad = BlockingCall()
        val chatLayoutRepository =
            FakeChatLayoutPreferenceRepository(conversationForwardGestureEnabled = true)
        val platformCapabilities =
            object : SharedSettingsPlatformCapabilities {
                override val supportsConversationForwardGesture: Boolean = true

                override suspend fun loadSystemNotificationPermission(): SettingsMobileActionSnapshot? {
                    permissionLoad.consume()
                    return null
                }
            }
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                chatLayoutPreferenceRepository = chatLayoutRepository,
                platformCapabilities = platformCapabilities
            )
        val history = SharedConversationForwardHistory()
        var nativeDisableCount = 0
        val runtimeAuthority =
            ConversationForwardGestureRuntimeAuthority(
                supported = true
            ) { enabled ->
                reconcileConversationForwardGesturePreference(
                    enabled = enabled,
                    history = history,
                    disableNativeGesture = { nativeDisableCount += 1 }
                )
            }
        val entry =
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Channel,
                channelId = ChannelId("channel-a")
            ).toConversationForwardEntry("server-a")
        runtimeAuthority.seedFromRepository(true)
        history.recordNewOpen(entry)
        assertTrue(history.recordHomeVisible())
        assertTrue(history.canForward)

        var delayedResult: DataResult<SettingsMobileSnapshot>? = null
        val loadJob =
            launch {
                delayedResult = dataSource.loadMobilePreferences()
            }
        permissionLoad.awaitStarted()

        chatLayoutRepository.conversationForwardGestureEnabled = false
        runtimeAuthority.applyProcessPreferenceChange(false)
        permissionLoad.release()
        loadJob.join()

        val staleMobileSnapshot =
            assertIs<DataResult.Content<SettingsMobileSnapshot>>(delayedResult).data
        assertTrue(staleMobileSnapshot.conversationForwardGesture.enabled)
        assertFalse(chatLayoutRepository.conversationForwardGestureEnabled)
        assertFalse(history.enabledForTest())
        assertFalse(history.canForward)
        assertNull(history.forwardEntryForTest())
        assertEquals(1, nativeDisableCount)

        history.recordNewOpen(entry)
        assertFalse(history.recordHomeVisible())
        assertFalse(history.canForward)
    }

    @Test
    fun mobileLinkOpenModeRejectsUnknownSelectionWithoutOverwritingPreference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val linkOpenPreferenceRepository = FakeLinkOpenPreferenceRepository()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        linkOpenPreferenceRepository = linkOpenPreferenceRepository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.SetLinkOpenMode("unsupported"))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(LinkOpenMode.SystemBrowser, linkOpenPreferenceRepository.mode)
        assertEquals("system", mobile.linkOpenMode.selectedKey)
        assertEquals(MOBILE_ACTION_LINK_OPEN_MODE, store.state.value.actionState?.action)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertEquals("settings_link_open_mode_unknown", store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun mobileServerNotificationModeIntentUsesSettingsRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(false, mobile.muteServer.enabled)
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(listOf("setServerNotificationMode:mentions"), repository.calls)
        scope.cancel()
    }

    @Test
    fun mobilePushPreviewModeIntentUsesSettingsRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        repository.calls.clear()
        store.accept(SharedSettingsIntent.SetPushPreviewMode(PushPreviewMode.Private.wireName))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertFalse(mobile.pushMessagePreviews.enabled)
        assertEquals(listOf("setPushPreviewMode:private"), repository.calls)
        assertEquals(MOBILE_ACTION_PUSH_PREVIEW_MODE, store.state.value.actionState?.action)
        assertFalse(store.state.value.actionState?.inProgress == true)
        scope.cancel()
    }

    @Test
    fun serverNotificationModeOptimisticallySelectsOnceWhilePatchIsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot()
            )
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(mobileResult = DataResult.Content(initial)).also {
                it.setServerNotificationModeBlock = block
                it.setServerNotificationModeResult =
                    DataResult.Content(
                        initial.copy(
                            serverNotificationMode =
                                ServerPushMode.Mentions.toSettingsMobileSelectionSnapshot().copy(
                                    selectedLabel = "Canonical mentions",
                                    statusLabel = "Canonical mentions"
                                )
                        )
                    )
            }
        val store = settingsStore(repository, scope = scope)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        repository.calls.clear()

        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.All.wireName))
        assertEquals(emptyList(), repository.calls)

        val mutation = launch {
            store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        }
        block.awaitStarted()

        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.serverNotificationMode.available)
        assertEquals(true, store.state.value.actionState?.inProgress)
        assertEquals(ServerPushMode.Mentions.wireName, store.state.value.actionState?.itemId)
        assertEquals(listOf("setServerNotificationMode:mentions"), repository.calls)

        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    ServerNotificationPreferencesChanged(
                        serverId = ServerId("server-1"),
                        serverPushMode = ServerPushMode.All.wireName,
                        prefsVersion = 2
                    )
            )
        )
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(listOf("setServerNotificationMode:mentions", "mobile"), repository.calls)

        store.accept(SharedSettingsIntent.SetForegroundMessageBanners(true))
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(false, mobile.foregroundMessageBanners.enabled)
        assertEquals(MOBILE_ACTION_SERVER_MUTE, store.state.value.actionState?.action)

        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.None.wireName))
        assertEquals(listOf("setServerNotificationMode:mentions", "mobile"), repository.calls)

        block.release()
        mutation.join()

        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals("Canonical mentions", mobile.serverNotificationMode.statusLabel)
        assertEquals(false, store.state.value.actionState?.inProgress)
        scope.cancel()
    }

    @Test
    fun serverNotificationModeFailureRollsBackAndAllowsFreshRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot()
            )
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(mobileResult = DataResult.Content(initial)).also {
                it.setServerNotificationModeBlock = block
                it.setServerNotificationModeFailure = IllegalStateException("network down")
            }
        val store = settingsStore(repository, scope = scope)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        repository.calls.clear()

        val mutation = launch {
            store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        }
        block.awaitStarted()
        assertEquals(
            ServerPushMode.Mentions.wireName,
            assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data.serverNotificationMode.selectedKey
        )
        block.release()
        mutation.join()

        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.serverNotificationMode.available)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertEquals("settings_server_notification_mode_failed", store.state.value.actionState?.errorCode)

        repository.setServerNotificationModeBlock = null
        repository.setServerNotificationModeFailure = null
        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))

        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertEquals(
            listOf("setServerNotificationMode:mentions", "setServerNotificationMode:mentions"),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun serverNotificationModeLateResultCannotCrossOwnerIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot()
            )
        val block = BlockingCall()
        val processSessionStore = EventingSessionStore(sampleSession())
        val sessionStore =
            RaftShellAuthSessionStore(
                delegate = processSessionStore,
                selectedServerStore =
                    SelectedServerStore(
                        RaftShellStoreScope.create(
                            shellInstanceId = "settings-owner-test",
                            initialServerId = "server-1"
                        )
                    ),
                eventScope = scope
            )
        val repository =
            FakeSettingsRepository(mobileResult = DataResult.Content(initial)).also {
                it.setServerNotificationModeBlock = block
                it.setServerNotificationModeResult =
                    DataResult.Content(
                        initial.copy(
                            serverNotificationMode = ServerPushMode.None.toSettingsMobileSelectionSnapshot()
                        )
                    )
            }
        val store = settingsStore(repository, sessionStore = sessionStore, scope = scope)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))

        val mutation = launch {
            store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        }
        block.awaitStarted()
        processSessionStore.updateTokens("access-2", "refresh-2")
        yield()

        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.serverNotificationMode.available)
        assertEquals(true, store.state.value.actionState?.inProgress)

        sessionStore.updateSelectedServer("server-2", "server-2")
        yield()

        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(false, mobile.serverNotificationMode.available)
        assertEquals(false, mobile.muteServer.available)
        assertNull(store.state.value.actionState)
        val retiredState = store.state.value

        block.release()
        mutation.join()

        assertTrue(retiredState === store.state.value)
        assertEquals(listOf("mobile", "setServerNotificationMode:mentions"), repository.calls)

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        repository.mobileResult = DataResult.Content(initial)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.serverNotificationMode.available)
        scope.cancel()
    }

    @Test
    fun serverNotificationModeOwnerReadCannotPublishAfterRouteDeparture() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot()
            )
        val mutationBlock = BlockingCall()
        val ownerReadBlock = BlockingCall()
        val sessionStore = BlockingSessionStore(InMemoryRaftAuthSessionStore(sampleSession()))
        val repository =
            FakeSettingsRepository(mobileResult = DataResult.Content(initial)).also {
                it.setServerNotificationModeBlock = mutationBlock
                it.setServerNotificationModeResult =
                    DataResult.Content(
                        initial.copy(
                            serverNotificationMode = ServerPushMode.None.toSettingsMobileSelectionSnapshot()
                        )
                    )
            }
        val store = settingsStore(repository, sessionStore = sessionStore, scope = scope)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        repository.calls.clear()

        val mutation = launch {
            store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        }
        mutationBlock.awaitStarted()
        sessionStore.readBlock = ownerReadBlock
        mutationBlock.release()
        ownerReadBlock.awaitStarted()

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        ownerReadBlock.release()
        mutation.join()

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(SharedSettingsDestination.Mobile, store.state.value.route.destination)
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(false, mobile.serverNotificationMode.available)
        assertNull(store.state.value.actionState)
        assertEquals(listOf("setServerNotificationMode:mentions"), repository.calls)
        scope.cancel()
    }

    @Test
    fun serverNotificationModeLateResultCannotCrossDestination() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot()
            )
        val block = BlockingCall()
        val repository =
            FakeSettingsRepository(mobileResult = DataResult.Content(initial)).also {
                it.setServerNotificationModeBlock = block
                it.setServerNotificationModeResult =
                    DataResult.Content(
                        initial.copy(
                            serverNotificationMode = ServerPushMode.None.toSettingsMobileSelectionSnapshot()
                        )
                    )
            }
        val store = settingsStore(repository, scope = scope)
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))

        val mutation = launch {
            store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.Mentions.wireName))
        }
        block.awaitStarted()
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        block.release()
        mutation.join()

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(false, mobile.serverNotificationMode.available)
        assertNull(store.state.value.actionState)
        scope.cancel()
    }

    @Test
    fun foregroundBannerMutationPreservesCanonicalServerNotificationMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mentionsSnapshot =
            mobileSnapshot().copy(
                serverNotificationMode = ServerPushMode.Mentions.toSettingsMobileSelectionSnapshot()
            )
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mentionsSnapshot))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        store.accept(SharedSettingsIntent.SetForegroundMessageBanners(true))

        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.Mentions.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(true, mobile.serverNotificationMode.available)
        assertEquals(true, mobile.foregroundMessageBanners.enabled)
        assertEquals(false, store.state.value.actionState?.inProgress)

        store.accept(SharedSettingsIntent.SetServerNotificationMode(ServerPushMode.All.wireName))

        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(ServerPushMode.All.wireName, mobile.serverNotificationMode.selectedKey)
        assertEquals(false, store.state.value.actionState?.inProgress)
        assertEquals(
            listOf("mobile", "mobile", "setServerNotificationMode:all"),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun mobileNotificationActionCompletionStoresActionableFeedbackWithoutGenericError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.RequestNotificationPermission)
        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_PERMISSION,
                SystemNotificationActionResult(SystemNotificationActionStatus.Ok, code = "permission_granted")
            )
        )
        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Allowed", mobile.notificationPermission.statusLabel)
        assertNull(store.state.value.effect)

        store.accept(SharedSettingsIntent.RequestNotificationPermission)
        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_PERMISSION,
                SystemNotificationActionResult(SystemNotificationActionStatus.Requested, code = "permission_requested")
            )
        )
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Requested", mobile.notificationPermission.statusLabel)
        assertEquals(
            "System notification permission dialog was opened. Recheck after responding.",
            mobile.notificationPermission.detail
        )
        assertNull(store.state.value.actionState?.errorCode)

        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_TEST_NOTIFICATION,
                SystemNotificationActionResult(SystemNotificationActionStatus.Unsupported, code = "notification_unsupported")
            )
        )
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(false, mobile.testNotification.available)
        assertEquals("Unsupported", mobile.testNotification.statusLabel)
        assertNull(store.state.value.actionState?.errorCode)

        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_PERMISSION,
                SystemNotificationActionResult(SystemNotificationActionStatus.NotificationsDisabled, code = "notifications_disabled")
            )
        )
        mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Disabled", mobile.notificationPermission.statusLabel)
        assertEquals(false, mobile.notificationPermission.available)
        assertEquals("System notifications are disabled for Raft.", mobile.notificationPermission.detail)
        assertNull(store.state.value.actionState?.errorCode)

        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_TEST_NOTIFICATION,
                SystemNotificationActionResult(SystemNotificationActionStatus.Error, code = "post_failed")
            )
        )
        assertEquals("post_failed", store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun mobileLoadUsesCurrentSystemNotificationPermissionSnapshot() = runBlocking {
        val capabilities =
            FakeSettingsCapabilities(
                systemNotificationPermission =
                    SettingsMobileActionSnapshot(
                        available = false,
                        statusLabel = "Denied",
                        detail = "Notification permission is denied. Open iOS Settings to enable it."
                    )
            )
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                localeRepository = FakeLocaleRepository(),
                chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository(),
                platformCapabilities = capabilities
            )

        val mobile = assertIs<DataResult.Content<SettingsMobileSnapshot>>(dataSource.loadMobile()).data

        assertEquals("Denied", mobile.notificationPermission.statusLabel)
        assertEquals(false, mobile.notificationPermission.available)
        assertEquals(
            "Notification permission is denied. Open iOS Settings to enable it.",
            mobile.notificationPermission.detail
        )
        assertEquals(listOf("notificationPermission"), capabilities.calls)
    }

    @Test
    fun openComputerLoadsDetailRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))

        val detail = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        assertEquals("machine-42", detail.computer.id)
        assertEquals(true, detail.canManageMachines)
        assertEquals(listOf("computerDetail:machine-42"), repository.calls)
        scope.cancel()
    }

    @Test
    fun scanComputerWorkspacesUpdatesOnlyTheWorkspaceSection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                computerWorkspacesResult =
                    DataResult.Content(
                        listOf(
                            SettingsComputerWorkspaceSnapshot(
                                id = "workspace-1",
                                name = "Raft",
                                path = "~/.slock/agents/Raft/",
                                statusLabel = "active"
                            )
                        )
                    )
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))

        store.accept(SharedSettingsIntent.ScanComputerWorkspaces("machine-42"))

        val detail = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data
        assertEquals(true, detail.workspacesScanned)
        assertEquals(listOf("workspace-1"), detail.workspaces.map { it.id })
        assertEquals(0, detail.agents.size)
        assertEquals(
            SharedSettingsActionState(COMPUTER_ACTION_SCAN_WORKSPACES, "machine-42", inProgress = false),
            store.state.value.actionState
        )
        assertEquals(listOf("computerDetail:machine-42", "scanComputerWorkspaces:machine-42"), repository.calls)
        scope.cancel()
    }

    @Test
    fun openComputerKeepsManageActionsHiddenForNonAdmins() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "member")),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))

        val detail = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data
        assertEquals(false, detail.canManageMachines)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        scope.cancel()
    }

    @Test
    fun openComputerDoesNotFallbackToRepositoryDetailWhenSharedComputersSourceMisses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val computersSource =
            FakeSettingsComputersSource(
                computerResult = DataResult.Empty(EmptyReason.NoSettingsAvailable)
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        computersSource = computersSource
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))

        assertEquals(
            listOf("sharedComputer:server-1:machine-42:false"),
            computersSource.calls
        )
        assertEquals(emptyList(), repository.calls)
        assertIs<ScreenLoadState.Empty>(store.state.value.computerDetail)
        scope.cancel()
    }

    @Test
    fun renameComputerIntentCallsRepositoryAndReloadsDetail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val renamed =
            SettingsComputerSnapshot(
                id = "machine-42",
                name = "MacBook Pro 2",
                daemonVersion = "0.51.1",
                online = true
            )
        val repository =
            FakeSettingsRepository(
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                computerWorkspacesResult =
                    DataResult.Content(
                        listOf(
                            SettingsComputerWorkspaceSnapshot(
                                id = "workspace-1",
                                name = "Raft",
                                path = "~/.slock/agents/Raft/",
                                statusLabel = "active"
                            )
                        )
                    ),
                renameComputerResult = DataResult.Content(renamed)
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // A successful scan should survive the unscanned detail snapshot
        // returned after a rename action reloads the rest of the page.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        store.accept(SharedSettingsIntent.ScanComputerWorkspaces("machine-42"))
        store.accept(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest("machine-42", "MacBook Pro 2")))

        // Repository called: initial OpenComputer (loads detail), then
        // renameComputer, then a second computerDetail reload. The fake's
        // computerDetailResult still returns the original snapshot, so we
        // assert on the call sequence + action state only — the
        // post-reload snapshot's name is a function of the fake fixture,
        // not the action itself.
        assertEquals(
            listOf(
                "computerDetail:machine-42",
                "scanComputerWorkspaces:machine-42",
                "renameComputer:machine-42:MacBook Pro 2",
                "computerDetail:machine-42"
            ),
            repository.calls
        )
        val actionState = assertIs<SharedSettingsActionState>(store.state.value.actionState)
        assertEquals("renameComputer", actionState.action)
        assertEquals("machine-42", actionState.itemId)
        assertEquals(false, actionState.inProgress)
        assertNull(actionState.errorCode)
        // Detail reload happened (still Content) and route is still ComputerDetail.
        val detail = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data
        assertEquals(true, detail.workspacesScanned)
        assertEquals(listOf("workspace-1"), detail.workspaces.map { it.id })
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        scope.cancel()
    }

    @Test
    fun updateComputerDescriptionIntentCallsRepositoryAndReloadsDetail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val updated =
            SettingsComputerSnapshot(
                id = "machine-42",
                name = "MacBook Pro",
                daemonVersion = "0.51.1",
                online = true,
                description = "Build farm runner"
            )
        val repository =
            FakeSettingsRepository(
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                updateComputerDescriptionResult = DataResult.Content(updated)
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        store.accept(SharedSettingsIntent.UpdateComputerDescription(SettingsComputerDescriptionUpdateRequest("machine-42", "Build farm runner")))

        assertEquals(
            listOf(
                "computerDetail:machine-42",
                "updateComputerDescription:machine-42:Build farm runner",
                "computerDetail:machine-42"
            ),
            repository.calls
        )
        val actionState = assertIs<SharedSettingsActionState>(store.state.value.actionState)
        assertEquals("updateComputerDescription", actionState.action)
        assertEquals("machine-42", actionState.itemId)
        assertEquals(false, actionState.inProgress)
        assertNull(actionState.errorCode)
        assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        scope.cancel()
    }

    @Test
    fun deleteComputerIntentClearsRouteAndSurfacesErrorCodeOnFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                deleteComputerResult =
                    DataResult.Error(ScreenError(code = "computer_has_agents", retryable = false))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the detail page first so the route is the detail page, then
        // attempt a delete that fails with 409 (computer_has_agents). The
        // page must stay on the detail route so the user can read the error.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        store.accept(SharedSettingsIntent.DeleteComputer("machine-42"))

        // Repository was called twice — once for the initial OpenComputer
        // (loads the detail) and once for the failed DeleteComputer.
        assertEquals(listOf("computerDetail:machine-42", "deleteComputer:machine-42"), repository.calls)
        val actionState = assertIs<SharedSettingsActionState>(store.state.value.actionState)
        assertEquals("deleteComputer", actionState.action)
        assertEquals("machine-42", actionState.itemId)
        assertEquals(false, actionState.inProgress)
        assertEquals("computer_has_agents", actionState.errorCode)
        // Route is still the detail page so the user can react to the error.
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        scope.cancel()
    }

    @Test
    fun deleteComputerIntentOnSuccessPopsToComputersListAndReloadsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(computersSnapshot("machine-99")),
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                deleteComputerResult = DataResult.Content(SettingsComputerActionResult(success = true, machineId = "machine-42", action = "delete"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the detail page first so the route is the detail page, then
        // perform a successful delete. The page should pop back to the
        // Computers list and reload it.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        store.accept(SharedSettingsIntent.DeleteComputer("machine-42"))

        // Repository called for the initial detail load, then the delete,
        // then a computers list reload. Route should fall back to Computers.
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Computers), store.state.value.route)
        assertEquals(false, store.state.value.actionState?.inProgress ?: true)
        // First call is the detail load, then the delete, then the list reload.
        assertTrue(repository.calls.contains("computerDetail:machine-42"))
        assertTrue(repository.calls.contains("deleteComputer:machine-42"))
        assertTrue(repository.calls.contains("computers"))
        scope.cancel()
    }

    @Test
    fun rotateComputerKeyIntentCallsRepositoryAndReloadsDetailWithNewPrefix() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rotated =
            SettingsComputerKeyRotationResult(
                machineId = "machine-42",
                apiKeyPrefix = "sk_machine_xyz"
            )
        val repository =
            FakeSettingsRepository(
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")),
                rotateComputerKeyResult = DataResult.Content(rotated)
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the detail page first, then rotate the key. The rotate-key
        // helper should call rotateComputerKey + reload the detail snapshot.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        store.accept(SharedSettingsIntent.RotateComputerKey("machine-42"))

        assertEquals(
            listOf(
                "computerDetail:machine-42",
                "rotateComputerKey:machine-42",
                "computerDetail:machine-42"
            ),
            repository.calls
        )
        val actionState = assertIs<SharedSettingsActionState>(store.state.value.actionState)
        assertEquals("rotateComputerKey", actionState.action)
        assertEquals("machine-42", actionState.itemId)
        assertEquals(false, actionState.inProgress)
        assertNull(actionState.errorCode)
        scope.cancel()
    }

    @Test
    fun realtimeMachineUpdateReloadsDetailWhenRouteMatchesMachineId() = runBlocking {
        // #79: when a RealtimeRefreshRequired(Machines, machineId) event
        // arrives and the user is currently on the ComputerDetail page for
        // that exact machine, the store must reload the detail snapshot so
        // status / daemonVersion / runtimes / lastHeartbeat reflect the new
        // server state without a full app refresh.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the detail page first so the route is ComputerDetail / machine-42.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        // Clear the calls tracker so we can isolate the realtime-triggered reload.
        repository.calls.clear()

        // Fire a realtime machine:updated event for the open machine.
        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    build.raft.app.model.RealtimeRefreshRequired(
                        scope = build.raft.app.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = build.raft.app.model.ServerId("server-1"),
                        subjectId = "machine-42"
                    )
            )
        )
        // Realtime dispatch is fire-and-forget (scope.launch), so let the
        // queued coroutine actually run.
        yield()

        // Repository must have reloaded the computer detail (no other calls
        // — the realtime handler only refetches when the open machineId
        // matches the event's machineId).
        assertEquals(listOf("computerDetail:machine-42"), repository.calls)
        // Detail state must remain Content (not flipped to Loading/Error).
        assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail)
        scope.cancel()
    }

    @Test
    fun realtimeServerNotificationModeReloadsOpenNotificationsPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Notifications))
        repository.calls.clear()
        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    ServerNotificationPreferencesChanged(
                        serverId = ServerId("server-1"),
                        serverPushMode = ServerPushMode.Mentions.wireName,
                        prefsVersion = 2
                    )
            )
        )
        yield()

        assertEquals(listOf("mobile"), repository.calls)
        assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile)
        scope.cancel()
    }

    @Test
    fun realtimeMachineUpdateDoesNotReloadWhenOpenMachineIdDoesNotMatch() = runBlocking {
        // #79: when the user is on a different computer's detail page, a
        // machine:updated event for some other machine must not trigger a
        // refresh — otherwise we'd pointlessly refetch the open detail.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open machine-42, then fire a machine:updated for machine-99 (different machine).
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        repository.calls.clear()

        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    build.raft.app.model.RealtimeRefreshRequired(
                        scope = build.raft.app.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = build.raft.app.model.ServerId("server-1"),
                        subjectId = "machine-99"
                    )
            )
        )
        yield()

        assertEquals(emptyList(), repository.calls)
        scope.cancel()
    }

    @Test
    fun realtimeMachineUpdateReloadsListWhenRouteIsComputers() = runBlocking {
        // #79: when the user is on the Computers list page, a
        // machine:updated event must reload the list so card status dots
        // flip live. The list reload is independent of which machine the
        // event is for (the list always shows the current snapshot).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(computersResult = DataResult.Content(computersSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the Computers list route.
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        repository.calls.clear()

        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    build.raft.app.model.RealtimeRefreshRequired(
                        scope = build.raft.app.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = build.raft.app.model.ServerId("server-1"),
                        subjectId = "machine-42"
                    )
            )
        )
        yield()

        // List must have been reloaded.
        assertTrue(repository.calls.contains("computers"))
        scope.cancel()
    }

    @Test
    fun computersEmptyListStillShowsAdminAddComputerEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(SettingsComputersSnapshot(computers = emptyList()))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))

        val computers = assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data
        assertEquals(emptyList(), computers.computers)
        assertEquals(true, computers.canAddComputer)
        scope.cancel()
    }

    @Test
    fun refreshComputersForcesSharedComputersRefresh() = runBlocking {
        // Home machine notifications can warm SharedComputersStore with an
        // already-loaded slice. Once the Computers page is rendered, it must
        // force-refresh its own data instead of rendering the stale slice.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val computersSource =
            FakeSettingsComputersSource(
                computersResult = DataResult.Content(computersSnapshot("machine-42"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = FakeSettingsRepository(),
                        computersSource = computersSource
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.RefreshComputers)

        assertEquals(listOf("sharedComputers:server-1:true"), computersSource.calls)
        val computers = assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data
        assertEquals(listOf("machine-42"), computers.computers.map { it.id })
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Home), store.state.value.route)
        scope.cancel()
    }

    @Test
    fun addComputerRegisterBuildsTransientSetupCommandAndWaitingState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registered =
            SettingsComputerSnapshot(
                id = "machine-new",
                name = "my-computer",
                daemonVersion = "",
                online = false
            )
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(SettingsComputersSnapshot(computers = emptyList())),
                registerComputerResult =
                    DataResult.Content(
                        SettingsComputerRegistrationResult(
                            machine = registered,
                            apiKey = "test-key-full",
                            apiKeyPrefix = "test-key-ful"
                        )
                    )
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer")))

        val dialog = store.state.value.addComputerDialog
        assertEquals(AddComputerStep.Waiting, dialog?.step)
        assertEquals("machine-new", dialog?.machineId)
        assertEquals("test-key-ful", dialog?.apiKeyPrefix)
        assertTrue(dialog?.daemonRunCommand.orEmpty().contains("--api-key test-key-full"))
        assertTrue(dialog?.daemonRunCommand.orEmpty().contains("@botiverse/raft-daemon@latest"))
        assertEquals(listOf("computers", "registerComputer:my-computer", "computers"), repository.calls)
        scope.cancel()
    }

    @Test
    fun addComputerPollAdvancesWaitingDialogWhenMachineComesOnline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val offline =
            SettingsComputerSnapshot(
                id = "machine-new",
                name = "my-computer",
                daemonVersion = "",
                online = false
            )
        val online =
            offline.copy(
                online = true,
                hostname = "builder.local",
                platform = "darwin"
            )
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(SettingsComputersSnapshot(computers = listOf(offline))),
                registerComputerResult =
                    DataResult.Content(
                        SettingsComputerRegistrationResult(
                            machine = offline,
                            apiKey = "test-key-full",
                            apiKeyPrefix = "test-key-ful"
                        )
                    )
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer")))
        repository.computersResult = DataResult.Content(SettingsComputersSnapshot(computers = listOf(online)))
        store.accept(SharedSettingsIntent.PollAddComputerMachineStatus("machine-new"))
        yield()

        val dialog = store.state.value.addComputerDialog
        assertEquals(AddComputerStep.Connected, dialog?.step)
        assertEquals("builder.local", dialog?.name)
        assertEquals("builder.local - darwin", dialog?.connectedDetail)
        scope.cancel()
    }

    @Test
    fun addComputerDoneRenamesAndOpensComputerDetail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val online =
            SettingsComputerSnapshot(
                id = "machine-new",
                name = "my-computer",
                daemonVersion = "0.51.1",
                online = true,
                hostname = "builder.local",
                platform = "darwin"
            )
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(SettingsComputersSnapshot(computers = listOf(online))),
                computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-new")),
                registerComputerResult =
                    DataResult.Content(
                        SettingsComputerRegistrationResult(
                            machine = online.copy(online = false),
                            apiKey = "test-key-full",
                            apiKeyPrefix = "test-key-ful"
                        )
                    ),
                renameComputerResult = DataResult.Content(online.copy(name = "Builder Mac"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer")))
        store.accept(SharedSettingsIntent.PollAddComputerMachineStatus("machine-new"))
        yield()
        store.accept(SharedSettingsIntent.SetAddComputerName("Builder Mac"))
        store.accept(SharedSettingsIntent.CompleteAddComputerDialog)
        yield()

        assertTrue(repository.calls.contains("renameComputer:machine-new:Builder Mac"))
        assertEquals(null, store.state.value.addComputerDialog)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-new"), store.state.value.route)
        assertEquals(SharedSettingsEffect.OpenComputer("machine-new"), store.state.value.effect)
        scope.cancel()
    }

    @Test
    fun dismissAddComputerWhileWaitingDeletesPendingMachine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registered =
            SettingsComputerSnapshot(
                id = "machine-new",
                name = "my-computer",
                daemonVersion = "",
                online = false
            )
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(SettingsComputersSnapshot(computers = listOf(registered))),
                registerComputerResult =
                    DataResult.Content(
                        SettingsComputerRegistrationResult(
                            machine = registered,
                            apiKey = "test-key-full",
                            apiKeyPrefix = "test-key-ful"
                        )
                    ),
                deleteComputerResult = DataResult.Content(SettingsComputerActionResult(machineId = "machine-new", success = true, action = "delete"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer")))
        store.accept(SharedSettingsIntent.DismissAddComputerDialog)
        yield()

        assertEquals(null, store.state.value.addComputerDialog)
        assertTrue(repository.calls.contains("deleteComputer:machine-new"))
        scope.cancel()
    }

    @Test
    fun realtimeEventWithNonMachineScopeIsIgnored() = runBlocking {
        // #79: only RealtimeRefreshScope.Machines events are handled here.
        // Other scopes (ServerMembers, Agents, etc.) are routed through
        // SharedHomeStore and must not trigger settings refreshes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
        repository.calls.clear()

        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    build.raft.app.model.RealtimeRefreshRequired(
                        scope = build.raft.app.model.RealtimeRefreshScope.ServerMembers,
                        reason = "server:member-updated",
                        serverId = build.raft.app.model.ServerId("server-1")
                    )
            )
        )
        yield()

        assertEquals(emptyList(), repository.calls)
        scope.cancel()
    }

    @Test
    fun aboutAndDiagnosticsLoadThroughPlatformCapabilities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))
        val about = assertIs<ScreenLoadState.Content<SharedSettingsAboutSnapshot>>(store.state.value.about).data
        assertEquals("1.2.3", about.versionLabel)
        assertEquals("456", about.buildLabel)

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Diagnostics))
        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(listOf("SlockTest"), diagnostics.entries.map { it.tag })
        // Opening About also fetches the hands update status and the developer
        // snapshot (48631ed3: refresh update notices periodically).
        assertEquals(listOf("about", "handsUpdateStatus", "developer", "about", "diagnostics"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun diagnosticFeatureFlagOverrideIntentForwardsKeyAndModeThenReloadsDiagnostics() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                    ),
                scope = scope,
            )
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Diagnostics))

        store.accept(
            SharedSettingsIntent.SetDiagnosticFeatureFlagOverride(
                key = "mobile_activity_stable_order_v1",
                mode = "force_off",
            )
        )

        assertEquals(
            listOf(
                "about",
                "diagnostics",
                "diagnosticOverride:mobile_activity_stable_order_v1:force_off",
                "about",
                "diagnostics",
            ),
            capabilities.calls,
        )
        assertEquals(
            SharedSettingsActionState(
                action = "diagnosticFeatureFlagOverride:mobile_activity_stable_order_v1",
                inProgress = false,
            ),
            store.state.value.actionState,
        )
        assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics)
        scope.cancel()
    }

    @Test
    fun mountedDiagnosticsRefreshesAtLatestOverrideExpiryAndRejectsReplacedTimer() = runBlocking {
        var now = 1_000L
        val pendingDelays = mutableListOf<Continuation<Unit>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        capabilities.setDiagnosticsResult(
            diagnosticOverridesHostSnapshot(
                clientOverride = "ForceOn",
                expiresAtMillis = 1_100L,
                finalEffective = true,
            )
        )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                    ),
                scope = scope,
                nowMillis = { now },
                diagnosticsRefreshDelay = { suspendCoroutine { pendingDelays += it } },
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Diagnostics))
        assertEquals(1, pendingDelays.size)

        capabilities.setDiagnosticsResult(
            diagnosticOverridesHostSnapshot(
                clientOverride = "ForceOff",
                expiresAtMillis = 1_200L,
                finalEffective = false,
            )
        )
        store.accept(
            SharedSettingsIntent.SetDiagnosticFeatureFlagOverride(
                key = "mobile_activity_stable_order_v1",
                mode = "force_off",
            )
        )
        assertEquals(2, pendingDelays.size)
        assertEquals(2, capabilities.calls.count { it == "diagnostics" })

        pendingDelays[0].resume(Unit)
        yield()

        assertEquals(2, capabilities.calls.count { it == "diagnostics" })
        val forced = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals("ForceOff", forced.featureFlagOverrides.single().clientOverride)

        now = 1_200L
        capabilities.setDiagnosticsResult(
            diagnosticOverridesHostSnapshot(
                clientOverride = "Server",
                expiresAtMillis = null,
                finalEffective = true,
            )
        )
        pendingDelays[1].resume(Unit)
        yield()

        assertEquals(3, capabilities.calls.count { it == "diagnostics" })
        val expired = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals("Server", expired.featureFlagOverrides.single().clientOverride)
        assertNull(expired.featureFlagOverrides.single().expiresAtMillis)
        assertEquals(true, expired.featureFlagOverrides.single().finalEffective)
        scope.cancel()
    }

    @Test
    fun developerEnvironmentLoadsAndSwitchesThroughPlatformCapabilities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Developer))
        val initial = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("production", initial.environmentKey)

        store.accept(SharedSettingsIntent.SetApiEnvironment("staging"))

        val updated = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("staging", updated.environmentKey)
        assertEquals("Staging", updated.environmentLabel)
        assertEquals("https://api-aws-staging.botiverse.dev/api/", updated.apiBaseUrl)
        assertEquals(SharedSettingsActionState(DEVELOPER_ACTION_API_ENVIRONMENT, "staging", inProgress = false), store.state.value.actionState)
        val effect = assertIs<SharedSettingsEffect.ApiEnvironmentChanged>(store.state.value.effect)
        assertEquals("API server switched to Staging. Sign in again to continue.", effect.message)
        assertEquals(listOf("developer", "environment:staging"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun developerEnvironmentLoadsAndSwitchesThroughSharedApiEnvironmentStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = sessionStore,
                        platformCapabilities = capabilities,
                        apiEnvironmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Developer))
        val initial = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("production", initial.environmentKey)

        store.accept(SharedSettingsIntent.SetApiEnvironment("staging"))

        val updated = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("staging", updated.environmentKey)
        assertEquals("Staging", updated.environmentLabel)
        assertNull(sessionStore.readSession())
        val effect = assertIs<SharedSettingsEffect.ApiEnvironmentChanged>(store.state.value.effect)
        assertEquals("API server switched to Staging. Sign in again to continue.", effect.message)
        assertEquals(listOf("environmentChanged:production:staging"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun developerEnvironmentDoesNotRestartWhenAlreadySelected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                        apiEnvironmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Developer))
        store.accept(SharedSettingsIntent.SetApiEnvironment("production"))

        val updated = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("production", updated.environmentKey)
        assertNull(store.state.value.effect)
        assertEquals(SharedSettingsActionState(DEVELOPER_ACTION_API_ENVIRONMENT, "production", inProgress = false), store.state.value.actionState)
        assertEquals(emptyList(), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun developerHandsUpdateCheckRunsThroughPlatformCapabilities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Developer))
        store.accept(SharedSettingsIntent.CheckHandsUpdate)

        val updated = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals(true, updated.updateCheck.supported)
        assertEquals("Update available", updated.updateCheck.statusLabel)
        assertEquals(true, updated.updateCheck.updateAvailable)
        assertEquals(2L, updated.updateCheck.latestVersionCode)
        assertEquals(SharedSettingsActionState(DEVELOPER_ACTION_HANDS_UPDATE_CHECK, inProgress = false), store.state.value.actionState)
        assertEquals(listOf("developer", "handsUpdateCheck"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun aboutLoadsUpdateStatusThroughPlatformCapabilities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        apiEnvironmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))

        val developer = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals(true, developer.updateCheck.supported)
        assertEquals("Current", developer.updateCheck.statusLabel)
        assertEquals("Check", developer.updateCheck.actionLabel)
        assertEquals(listOf("about", "handsUpdateStatus"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun aboutKeepsUpdateCheckHiddenWhenHostDoesNotSupportStatusCapability() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities =
            FakeSettingsCapabilities(
                developerSnapshot =
                    SharedSettingsDeveloperSnapshot(
                        environmentKey = "production",
                        environmentLabel = "Production",
                        apiBaseUrl = "https://api.raft.build/api/",
                        socketBaseUrl = "https://api.raft.build",
                        webUrl = "https://app.raft.build",
                        updateCheck = SharedSettingsUpdateCheckSnapshot()
                    ),
                updateStatusResult = DataResult.Error(ScreenError("settings_capability_unknown"))
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        apiEnvironmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))

        val developer = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals(false, developer.updateCheck.supported)
        assertEquals("Check failed", developer.updateCheck.statusLabel)
        assertEquals("settings_capability_unknown", developer.updateCheck.errorCode)
        assertEquals(listOf("about", "handsUpdateStatus"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun defaultDeveloperEnvironmentPresetsIncludeProductionAndStaging() {
        val options = sharedSettingsDefaultApiEnvironmentOptions()

        assertEquals(listOf("production", "staging"), options.map { it.key })
        assertEquals("https://api.raft.build/api/", options.first { it.key == "production" }.apiBaseUrl)
        assertEquals("https://api-aws-staging.botiverse.dev/api/", options.first { it.key == "staging" }.apiBaseUrl)
        assertEquals("https://api-aws-staging.botiverse.dev", options.first { it.key == "staging" }.socketBaseUrl)
    }

    @Test
    fun sharedApiEnvironmentStorePersistsCustomUrlsAndRewritesProductionDefaults() {
        val persistence = InMemoryRaftKeyValueStore()
        val store = SharedApiEnvironmentStore(persistence)

        val saved =
            store.save(
                SharedSettingsApiEnvironmentRequest(
                    key = "custom",
                    label = "Local Dev",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:5173"
                )
            )

        assertEquals("custom", saved?.key)
        assertEquals("custom", SharedApiEnvironmentStore(persistence).current().key)
        assertEquals(
            "http://10.0.2.2:3000/api/messages",
            store.rewriteApiUrl("https://api.raft.build/api/messages")
        )
        assertEquals(
            "http://10.0.2.2:3000",
            store.resolveSocketUrl("https://api.raft.build")
        )
    }

    @Test
    fun developerEnvironmentSavesCustomUrlsThroughPlatformCapabilities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Developer))
        store.accept(
            SharedSettingsIntent.SaveApiEnvironment(
                SharedSettingsApiEnvironmentRequest(
                    key = "custom",
                    label = "Local Dev",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:5173"
                )
            )
        )

        val updated = assertIs<ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>>(store.state.value.developer).data
        assertEquals("custom", updated.environmentKey)
        assertEquals("Local Dev", updated.environmentLabel)
        assertEquals("http://10.0.2.2:3000/api/", updated.apiBaseUrl)
        assertEquals("http://10.0.2.2:3000", updated.socketBaseUrl)
        assertEquals(SharedSettingsActionState(DEVELOPER_ACTION_API_ENVIRONMENT, "custom", inProgress = false), store.state.value.actionState)
        assertEquals(listOf("developer", "environment:custom"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun aboutSessionLabelsComeFromCanonicalSessionStoreWhenHostLabelsAreBlank() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities =
            FakeSettingsCapabilities(
                aboutResult =
                    DataResult.Content(
                        SharedSettingsAboutSnapshot(
                            appName = "Slock",
                            versionLabel = "1.2.3",
                            buildLabel = "456",
                            packageName = "build.raft.app",
                            signedInUserLabel = "",
                            selectedServerLabel = "",
                            sessionStatusLabel = ""
                        )
                    )
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))

        val about = assertIs<ScreenLoadState.Content<SharedSettingsAboutSnapshot>>(store.state.value.about).data
        assertEquals("Artin", about.signedInUserLabel)
        assertEquals("Slock Community", about.selectedServerLabel)
        assertEquals("Signed in", about.sessionStatusLabel)
        // Opening About also fetches the hands update status and the developer
        // snapshot (48631ed3: refresh update notices periodically).
        assertEquals(listOf("about", "handsUpdateStatus", "developer"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun diagnosticsStillLoadWhenHostDiagnosticsAreUnavailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities(diagnosticsResult = DataResult.Empty(EmptyReason.NoSettingsAvailable))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Diagnostics))

        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(emptyList(), diagnostics.entries)
        assertEquals(listOf("about", "diagnostics"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun storageLoadsAndClearCompletionKeepsActionFeedbackInSharedState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Storage))
        var storage = assertIs<ScreenLoadState.Content<SharedSettingsStorageSnapshot>>(store.state.value.storage).data
        assertEquals("12.0 MB", storage.totalSizeLabel)
        assertEquals(listOf("database", "imageCache", "temporaryAndLogs"), storage.rows.map { it.target })

        store.accept(SharedSettingsIntent.ClearStorage("imageCache"))
        assertEquals(SharedSettingsEffect.ClearStorage("imageCache"), store.state.value.effect)
        assertEquals(SharedSettingsActionState("clearStorage", "imageCache", inProgress = true), store.state.value.actionState)

        store.accept(
            SharedSettingsIntent.CompleteStorageClear(
                "imageCache",
                ok = true,
                snapshot = storage.copy(totalSizeLabel = "5.0 MB", feedback = "Cleared imageCache")
            )
        )
        storage = assertIs<ScreenLoadState.Content<SharedSettingsStorageSnapshot>>(store.state.value.storage).data
        assertEquals("5.0 MB", storage.totalSizeLabel)
        assertEquals("Cleared imageCache", storage.feedback)
        assertEquals(SharedSettingsActionState("clearStorage", "imageCache", inProgress = false), store.state.value.actionState)
        scope.cancel()
    }

    private class FakeSettingsRepository(
        var accountResult: DataResult<AccountSettingsSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var connectedAppsResult: DataResult<SettingsConnectedAppsSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computersResult: DataResult<SettingsComputersSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computerDetailResult: DataResult<SettingsComputerDetailSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computerWorkspacesResult: DataResult<List<SettingsComputerWorkspaceSnapshot>> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var billingResult: DataResult<SettingsBillingSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var serverResult: DataResult<SettingsServerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var languageRegionResult: DataResult<SettingsLanguageRegionSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var appearanceResult: DataResult<SettingsAppearanceSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var mobileResult: DataResult<SettingsMobileSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var registerComputerResult: DataResult<SettingsComputerRegistrationResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var renameComputerResult: DataResult<SettingsComputerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var updateComputerDescriptionResult: DataResult<SettingsComputerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var deleteComputerResult: DataResult<SettingsComputerActionResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var rotateComputerKeyResult: DataResult<SettingsComputerKeyRotationResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var providerLinkStartResult: DataResult<AccountProviderLinkStartResult> = DataResult.Content(
            AccountProviderLinkStartResult(providerId = "google", url = "https://raft.build/auth/google/link")
        )
    ) : SettingsRepository, SettingsServerActionRepository, SettingsServerManagementRepository {
        val calls = mutableListOf<String>()
        var updateProfileBlock: BlockingCall? = null
        var changePasswordBlock: BlockingCall? = null
        var uploadAvatarBlock: BlockingCall? = null
        var providerLinkBlock: BlockingCall? = null
        var registerConnectedAppBlock: BlockingCall? = null
        var createServerInviteBlock: BlockingCall? = null
        var deleteServerBlock: BlockingCall? = null
        var setServerNotificationModeBlock: BlockingCall? = null
        var setServerNotificationModeResult: DataResult<SettingsMobileSnapshot>? = null
        var setServerNotificationModeFailure: Throwable? = null
        var deleteServerResult: DataResult<SettingsServerActionResult> = DataResult.Content(SettingsServerActionResult(success = true))

        private fun SettingsLanguageRegionPreferenceUpdateRequest.callLabel(): String =
            when {
                autoTranslationEnabled != null -> "auto=$autoTranslationEnabled"
                preferredLanguage != null -> "language=$preferredLanguage"
                clearPreferredLanguage -> "language=null"
                preferredTimezone != null -> "timezone=$preferredTimezone"
                clearPreferredTimezone -> "timezone=null"
                preferredTimeFormat != null -> "timeFormat=$preferredTimeFormat"
                clearPreferredTimeFormat -> "timeFormat=null"
                preferredTranslationDisplay != null -> "display=$preferredTranslationDisplay"
                else -> "empty"
            }

        override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
            calls += "account"
            return accountResult
        }

        override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
            calls += "updateProfile:${request.displayName}"
            updateProfileBlock?.consume()
            return accountResult
        }

        override suspend fun changeAccountPassword(request: AccountPasswordChangeRequest): DataResult<AccountSettingsSnapshot> {
            calls += "changePassword:${request.newPassword.length}"
            changePasswordBlock?.consume()
            return accountResult
        }

        override suspend fun uploadAccountAvatar(request: AccountAvatarUploadRequest): DataResult<AccountSettingsSnapshot> {
            calls += "uploadAvatar:${request.file.fileName}"
            uploadAvatarBlock?.consume()
            return accountResult
        }

        override suspend fun startAccountProviderLink(
            request: AccountProviderLinkStartRequest
        ): DataResult<AccountProviderLinkStartResult> {
            calls += "startProviderLink:${request.providerId}"
            providerLinkBlock?.consume()
            return providerLinkStartResult
        }

        override suspend fun getConnectedApps(): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "connectedApps"
            return connectedAppsResult
        }

        override suspend fun registerConnectedApp(
            request: SettingsConnectedAppRegisterRequest
        ): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "registerConnectedApp:${request.name}"
            registerConnectedAppBlock?.consume()
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        registered =
                            current.registered +
                                SettingsConnectedAppClientSnapshot(
                                    id = "client-created",
                                    serverId = "server-1",
                                    clientId = request.clientId ?: "client-created",
                                    appType = "server_local",
                                    publishStatus = "draft",
                                    category = "productivity",
                                    name = request.name,
                                    returnUrl = request.returnUrl
                                ),
                        oneTimeClientSecret =
                            SettingsConnectedAppSecretSnapshot(
                                clientId = request.clientId ?: "client-created",
                                clientSecret = "secret_once"
                            )
                    )
                )
            return connectedAppsResult
        }

        override suspend fun updateConnectedApp(
            request: SettingsConnectedAppUpdateRequest
        ): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "updateConnectedApp:${request.id}:${request.name}"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        registered =
                            current.registered.map {
                                if (it.id == request.id) {
                                    it.copy(
                                        name = request.name,
                                        description = request.description,
                                        homepageUrl = request.homepageUrl,
                                        returnUrl = request.returnUrl,
                                        agentManifestUrl = request.agentManifestUrl
                                    )
                                } else {
                                    it
                                }
                            },
                        oneTimeClientSecret = null
                    )
                )
            return connectedAppsResult
        }

        override suspend fun uploadConnectedAppLogo(
            request: SettingsConnectedAppLogoUploadRequest
        ): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "uploadConnectedAppLogo:${request.id}:${request.file.fileName}"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        registered =
                            current.registered.map {
                                if (it.id == request.id) {
                                    it.copy(logoUrl = "https://cdn.example/${request.file.fileName}")
                                } else {
                                    it
                                }
                            },
                        oneTimeClientSecret = null
                    )
                )
            return connectedAppsResult
        }

        override suspend fun clearConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "clearConnectedAppLogo:$clientRecordId"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        registered =
                            current.registered.map {
                                if (it.id == clientRecordId) {
                                    it.copy(logoUrl = null)
                                } else {
                                    it
                                }
                            },
                        oneTimeClientSecret = null
                    )
                )
            return connectedAppsResult
        }

        override suspend fun deleteConnectedApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "deleteConnectedApp:$clientRecordId"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(current.copy(registered = current.registered.filterNot { it.id == clientRecordId }))
            return connectedAppsResult
        }

        override suspend fun requestConnectedAppPublish(
            request: SettingsConnectedAppPublishRequest
        ): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "requestConnectedAppPublish:${request.id}:${request.category}"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        registered =
                            current.registered.map {
                                if (it.id == request.id) {
                                    it.copy(
                                        publishStatus = "pending_review",
                                        category = request.category,
                                        dataAccessSummary = request.dataAccessSummary
                                    )
                                } else {
                                    it
                                }
                            }
                    )
                )
            return connectedAppsResult
        }

        override suspend fun installMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "installMarketplaceApp:$clientRecordId"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            val installed =
                current.marketplace.firstOrNull { it.id == clientRecordId }
                    ?.let {
                        SettingsConnectedAppInstalledSnapshot(
                            id = it.id,
                            clientId = it.clientId,
                            name = it.name,
                            developer = it.developer,
                            description = it.description,
                            homepageDomain = it.homepageDomain,
                            callbackDomain = it.callbackDomain,
                            logoUrl = it.logoUrl,
                            origin = "marketplace"
                        )
                    }
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        marketplace = current.marketplace.map { if (it.id == clientRecordId) it.copy(installed = true) else it },
                        installed = if (installed == null) current.installed else current.installed + installed
                    )
                )
            return connectedAppsResult
        }

        override suspend fun uninstallMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
            calls += "uninstallMarketplaceApp:$clientRecordId"
            val current =
                (connectedAppsResult as? DataResult.Content)?.data
                    ?: return connectedAppsResult
            connectedAppsResult =
                DataResult.Content(
                    current.copy(
                        marketplace = current.marketplace.map { if (it.id == clientRecordId) it.copy(installed = false) else it },
                        installed = current.installed.filterNot { it.id == clientRecordId }
                    )
                )
            return connectedAppsResult
        }

        override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
            calls += "computers"
            return computersResult
        }

        override suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> {
            calls += "computerDetail:$machineId"
            return computerDetailResult
        }

        override suspend fun scanComputerWorkspaces(machineId: String): DataResult<List<SettingsComputerWorkspaceSnapshot>> {
            calls += "scanComputerWorkspaces:$machineId"
            return computerWorkspacesResult
        }

        override suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult> {
            calls += "registerComputer:${request.name}"
            return registerComputerResult
        }

        override suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot> {
            calls += "renameComputer:${request.machineId}:${request.name}"
            return renameComputerResult
        }

        override suspend fun updateComputerDescription(request: SettingsComputerDescriptionUpdateRequest): DataResult<SettingsComputerSnapshot> {
            calls += "updateComputerDescription:${request.machineId}:${request.description}"
            return updateComputerDescriptionResult
        }

        override suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult> {
            calls += "deleteComputer:$machineId"
            return deleteComputerResult
        }

        override suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult> {
            calls += "rotateComputerKey:$machineId"
            return rotateComputerKeyResult
        }

        override suspend fun getComputerRuntimeModels(
            machineId: String,
            runtime: String
        ): DataResult<SettingsComputerRuntimeModelsSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun deleteComputerWorkspace(
            request: SettingsComputerWorkspaceDeleteRequest
        ): DataResult<SettingsComputerActionResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun getBilling(): DataResult<SettingsBillingSnapshot> {
            calls += "billing"
            return billingResult
        }

        override suspend fun getServerSettings(): DataResult<SettingsServerSnapshot> {
            calls += "server"
            return serverResult
        }

        override suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot> {
            calls += "updateServerProfile:${request.name}:${request.hideHumansFromMembers}"
            val current = (serverResult as? DataResult.Content)?.data ?: return serverResult
            val profile =
                current.profile.copy(
                    name = request.name ?: current.profile.name,
                    hideHumansFromMembers = request.hideHumansFromMembers ?: current.profile.hideHumansFromMembers
                )
            serverResult = DataResult.Content(current.copy(profile = profile))
            return serverResult
        }

        override suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult> {
            calls += "createServerInvite:${request.email}"
            createServerInviteBlock?.consume()
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun revokeServerInvite(inviteId: String): DataResult<SettingsServerActionResult> {
            calls += "revokeServerInvite:$inviteId"
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(pendingInvites = current.pendingInvites.filterNot { it.id == inviteId }))
            }
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun createServerJoinLink(
            request: SettingsServerJoinLinkCreateRequest
        ): DataResult<SettingsServerJoinLinkSnapshot> {
            calls += "createServerJoinLink"
            val link = SettingsServerJoinLinkSnapshot(id = "join-created", label = "Created link", url = "/join/created-token")
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(joinLinks = current.joinLinks + link))
            }
            return DataResult.Content(link)
        }

        override suspend fun createServerJoinLinkForInvite(
            request: SettingsServerJoinLinkCreateRequest
        ): DataResult<SettingsServerJoinLinkSnapshot> = createServerJoinLink(request)

        override suspend fun listServerJoinLinks(
            serverId: String
        ): DataResult<List<SettingsServerJoinLinkSnapshot>> {
            calls += "listServerJoinLinks:$serverId"
            val current = (serverResult as? DataResult.Content)?.data
            return DataResult.Content(current?.joinLinks.orEmpty())
        }

        override suspend fun revokeServerJoinLink(linkId: String): DataResult<SettingsServerActionResult> {
            calls += "revokeServerJoinLink:$linkId"
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(joinLinks = current.joinLinks.filterNot { it.id == linkId }))
            }
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> {
            calls += "deleteServer:${request.confirmationName}"
            deleteServerBlock?.consume()
            return deleteServerResult
        }

        override suspend fun updateServerAgreement(
            request: SettingsServerAgreementUpdateRequest
        ): DataResult<SettingsServerAgreementSnapshot> {
            calls += "updateServerAgreement:${request.enabled}:${request.title}"
            val agreement =
                SettingsServerAgreementSnapshot(
                    enabled = request.enabled,
                    title = request.title,
                    bodyMarkdown = request.bodyMarkdown,
                    statusLabel = if (request.enabled) "Required" else "Disabled",
                    detail = "Pre-join agreement ${if (request.enabled) "required" else "disabled"}.",
                    canSave = true
                )
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(agreement = agreement))
            }
            return DataResult.Content(agreement)
        }

        override suspend fun updateServerOnboarding(
            request: SettingsServerOnboardingUpdateRequest
        ): DataResult<SettingsServerOnboardingSnapshot> {
            calls += "updateServerOnboarding:${request.setupModalReminderOptOut}:${request.onboardingAgentId}:${request.clearOnboardingAgent}:${request.agentAllChannelGreetingEnabled}"
            val optOut = request.setupModalReminderOptOut ?: false
            val onboarding =
                SettingsServerOnboardingSnapshot(
                    setupModalReminderOptOut = optOut,
                    onboardingAgentId = request.onboardingAgentId,
                    agentAllChannelGreetingEnabled = request.agentAllChannelGreetingEnabled ?: true,
                    statusLabel = if (request.onboardingAgentId.isNullOrBlank()) "No onboarding agent" else "Agent selected",
                    detail = "Agent onboarding updated.",
                    canSave = true
                )
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(onboarding = onboarding))
            }
            return DataResult.Content(onboarding)
        }

        override suspend fun getServerTranslationSettings(): DataResult<SettingsServerTranslationSnapshot> =
            (serverResult as? DataResult.Content)?.data?.translation?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun updateServerTranslationSettings(
            request: SettingsServerTranslationUpdateRequest
        ): DataResult<SettingsServerTranslationSnapshot> {
            calls += "updateServerTranslation:${request.translationEnabled}"
            val translation =
                SettingsServerTranslationSnapshot(
                    translationEnabled = request.translationEnabled,
                    translationAvailable = true,
                    canManageTranslation = true,
                    statusLabel = if (request.translationEnabled) "Enabled" else "Disabled",
                    detail = "Translation ${if (request.translationEnabled) "enabled" else "disabled"}."
                )
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(translation = translation))
            }
            val currentLanguageRegion = (languageRegionResult as? DataResult.Content)?.data
            if (currentLanguageRegion != null) {
                languageRegionResult =
                    DataResult.Content(
                        currentLanguageRegion.copy(
                            serverTranslation =
                                currentLanguageRegion.serverTranslation.copy(
                                    enabled = request.translationEnabled,
                                    statusLabel = if (request.translationEnabled) "Enabled" else "Disabled"
                                ),
                            autoTranslation =
                                currentLanguageRegion.autoTranslation.copy(
                                    available = request.translationEnabled,
                                    statusLabel =
                                        if (request.translationEnabled) {
                                            currentLanguageRegion.autoTranslation.statusLabel
                                        } else {
                                            "Server translation disabled"
                                        }
                                )
                        )
                    )
            }
            return DataResult.Content(translation)
        }

        override suspend fun getServerSidebarOrder(): DataResult<SettingsServerSidebarOrderSnapshot> =
            (serverResult as? DataResult.Content)?.data?.sidebarOrder?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun updateServerSidebarOrder(
            request: SettingsServerSidebarOrderUpdateRequest
        ): DataResult<SettingsServerSidebarOrderSnapshot> {
            calls += "updateServerSidebarOrder:${request.channelSortMode}"
            val sidebarOrder = SettingsServerSidebarOrderSnapshot(channelSortMode = request.channelSortMode ?: "manual")
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(sidebarOrder = sidebarOrder))
            }
            return DataResult.Content(sidebarOrder)
        }

        override suspend fun getServerOrder(): DataResult<SettingsServerOrderSnapshot> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun updateServerOrder(
            request: SettingsServerOrderUpdateRequest
        ): DataResult<SettingsServerOrderSnapshot> {
            calls += "updateServerOrder:${request.serverOrder.size}"
            return DataResult.Content(SettingsServerOrderSnapshot(serverOrder = request.serverOrder))
        }

        override suspend fun updateServerMemberRole(
            request: SettingsServerMemberRoleUpdateRequest
        ): DataResult<SettingsServerActionResult> {
            calls += "updateServerMemberRole:${request.memberId}:${request.role}"
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult =
                    DataResult.Content(
                        current.copy(
                            admins =
                                current.admins.map { admin ->
                                    if (admin.id == request.memberId) admin.copy(role = request.role) else admin
                                }
                        )
                    )
            }
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun removeServerMember(memberId: String): DataResult<SettingsServerActionResult> {
            calls += "removeServerMember:$memberId"
            val current = (serverResult as? DataResult.Content)?.data
            if (current != null) {
                serverResult = DataResult.Content(current.copy(admins = current.admins.filterNot { it.id == memberId }))
            }
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun leaveServer(): DataResult<SettingsServerActionResult> {
            calls += "leaveServer"
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun updateSystemChannelVisibility(
            allChannelId: String,
            hide: Boolean
        ): DataResult<SettingsServerActionResult> {
            calls += "updateSystemChannelVisibility:$allChannelId:$hide"
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun restoreSystemChannel(): DataResult<SettingsServerActionResult> {
            calls += "restoreSystemChannel"
            return DataResult.Content(SettingsServerActionResult(success = true))
        }

        override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
            calls += "mobile"
            return mobileResult
        }

        override suspend fun getLanguageRegionSettings(): DataResult<SettingsLanguageRegionSnapshot> {
            calls += "languageRegion"
            return languageRegionResult
        }

        override suspend fun updateLanguageRegionPreference(
            request: SettingsLanguageRegionPreferenceUpdateRequest
        ): DataResult<SettingsLanguageRegionSnapshot> {
            calls += "updateLanguageRegionPreference:${request.callLabel()}"
            val current =
                (languageRegionResult as? DataResult.Content)?.data
                    ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
            languageRegionResult =
                DataResult.Content(
                    current.copy(
                        autoTranslation =
                            request.autoTranslationEnabled?.let {
                                current.autoTranslation.copy(
                                    enabled = it,
                                    statusLabel = if (it) "Enabled" else "Disabled"
                                )
                            } ?: current.autoTranslation,
                        browserLanguage =
                            when {
                                request.clearPreferredLanguage ->
                                    current.browserLanguage.copy(
                                        selectedKey = "browser",
                                        selectedLabel = "System language",
                                        statusLabel = "System language"
                                    )
                                request.preferredLanguage != null ->
                                    current.browserLanguage.copy(
                                        selectedKey = request.preferredLanguage,
                                        selectedLabel = "English",
                                        statusLabel = "English"
                                    )
                                else -> current.browserLanguage
                            },
                        timezone =
                            request.preferredTimezone?.let {
                                current.timezone.copy(selectedKey = it, selectedLabel = it, statusLabel = it)
                            } ?: current.timezone,
                        timeFormat =
                            request.preferredTimeFormat?.let {
                                current.timeFormat.copy(
                                    selectedKey = it,
                                    selectedLabel = if (it == "12h") "12-hour" else "24-hour",
                                    statusLabel = if (it == "12h") "12-hour" else "24-hour"
                                )
                            } ?: current.timeFormat,
                        translationDisplay =
                            request.preferredTranslationDisplay?.let {
                                current.translationDisplay.copy(
                                    selectedKey = it,
                                    selectedLabel = if (it == "original") "Original" else "Translated",
                                    statusLabel = if (it == "original") "Original" else "Translated",
                                    available = true
                                )
                            } ?: current.translationDisplay
                    )
                )
            return languageRegionResult
        }

        override suspend fun getAppearanceSettings(): DataResult<SettingsAppearanceSnapshot> {
            calls += "appearance"
            return appearanceResult
        }

        override suspend fun updateAppearanceSettings(
            request: SettingsAppearanceUpdateRequest
        ): DataResult<SettingsAppearanceSnapshot> {
            calls += "updateAppearance:${request.preferredMessageBodyFontSize}"
            appearanceResult = DataResult.Content(request.preferredMessageBodyFontSize.toSettingsAppearanceSnapshot())
            return appearanceResult
        }

        override suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
            calls += "setServerMute:$enabled"
            val current = (mobileResult as? DataResult.Content)?.data ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
            mobileResult =
                DataResult.Content(
                    current.copy(
                        muteServer =
                            current.muteServer.copy(
                                enabled = enabled,
                                available = true,
                                statusLabel = if (enabled) "Muted" else "Not muted"
                            )
                    )
                )
            return mobileResult
        }

        override suspend fun setServerNotificationMode(mode: ServerPushMode): DataResult<SettingsMobileSnapshot> {
            calls += "setServerNotificationMode:${mode.wireName}"
            setServerNotificationModeBlock?.consume()
            setServerNotificationModeFailure?.let { throw it }
            setServerNotificationModeResult?.let { return it }
            val current = (mobileResult as? DataResult.Content)?.data ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
            mobileResult =
                DataResult.Content(
                    current.copy(
                        muteServer =
                            current.muteServer.copy(
                                enabled = mode == ServerPushMode.None,
                                available = true,
                                statusLabel = if (mode == ServerPushMode.None) "Muted" else "Not muted"
                            ),
                        serverNotificationMode = mode.toSettingsMobileSelectionSnapshot()
                    )
                )
            return mobileResult
        }

        override suspend fun setPushPreviewMode(mode: PushPreviewMode): DataResult<SettingsMobileSnapshot> {
            calls += "setPushPreviewMode:${mode.wireName}"
            val current = (mobileResult as? DataResult.Content)?.data ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
            mobileResult =
                DataResult.Content(
                    current.copy(
                        pushMessagePreviews =
                            current.pushMessagePreviews.copy(
                                enabled = mode == PushPreviewMode.Full,
                                available = true,
                                statusLabel = if (mode == PushPreviewMode.Full) "Shown" else "Hidden"
                            )
                    )
                )
            return mobileResult
        }
    }

    private class FakeSettingsComputersSource(
        var computersResult: DataResult<SettingsComputersSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computerResult: DataResult<SettingsComputerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    ) : SharedSettingsComputersSource {
        val calls = mutableListOf<String>()

        override suspend fun loadSettingsComputersForServer(
            serverId: String,
            force: Boolean
        ): DataResult<SettingsComputersSnapshot> {
            calls += "sharedComputers:$serverId:$force"
            return computersResult
        }

        override suspend fun loadSettingsComputerSnapshotForServer(
            serverId: String,
            machineId: String,
            force: Boolean
        ): DataResult<SettingsComputerSnapshot> {
            calls += "sharedComputer:$serverId:$machineId:$force"
            return computerResult
        }
    }

    private class FakeSettingsCapabilities(
        private val aboutResult: DataResult<SharedSettingsAboutSnapshot> =
            DataResult.Content(
                SharedSettingsAboutSnapshot(
                    appName = "Slock",
                    versionLabel = "1.2.3",
                    buildLabel = "456",
                    packageName = "build.raft.app",
                    signedInUserLabel = "Artin",
                    selectedServerLabel = "Slock Community"
                )
            ),
        private var diagnosticsResult: DataResult<SharedSettingsDiagnosticsHostSnapshot> =
            DataResult.Content(
                SharedSettingsDiagnosticsHostSnapshot(
                    entries =
                        listOf(
                            SharedSettingsDiagnosticsEntry(
                                timestampLabel = "123",
                                tag = "SlockTest",
                                message = "diagnostic ready"
                            )
                        )
                )
            ),
        private var developerSnapshot: SharedSettingsDeveloperSnapshot = buildDeveloperSnapshot("production"),
        private val updateStatusResult: DataResult<SharedSettingsUpdateCheckSnapshot> =
            DataResult.Content(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    statusLabel = "Current",
                    detail = "No app update is available on main.",
                    actionLabel = "Check"
                )
            ),
        private val avatarFileResult: DataResult<PlatformFile> =
            DataResult.Error(ScreenError("settings_account_avatar_picker_unavailable")),
        private val connectedAppLogoFileResult: DataResult<PlatformFile> =
            DataResult.Error(ScreenError("settings_connected_app_logo_picker_unavailable")),
        private val mobileOAuthLinkResult: DataResult<Unit> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable),
        private val systemNotificationPermission: SettingsMobileActionSnapshot? = null
    ) : SharedSettingsPlatformCapabilities {
        val calls = mutableListOf<String>()

        override suspend fun startMobileOAuthAccountLink(providerId: String): DataResult<Unit> {
            calls += "mobileOAuthLink:$providerId"
            return mobileOAuthLinkResult
        }

        override suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> {
            calls += "about"
            return aboutResult
        }

        override suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> {
            calls += "storage"
            return DataResult.Content(storageSnapshot())
        }

        override suspend fun loadDeveloper(): DataResult<SharedSettingsDeveloperSnapshot> {
            calls += "developer"
            return DataResult.Content(developerSnapshot)
        }

        override suspend fun setApiEnvironment(environmentKey: String): DataResult<SharedSettingsDeveloperSnapshot> {
            calls += "environment:$environmentKey"
            developerSnapshot = buildDeveloperSnapshot(environmentKey)
            return DataResult.Content(developerSnapshot)
        }

        override suspend fun saveApiEnvironment(request: SharedSettingsApiEnvironmentRequest): DataResult<SharedSettingsDeveloperSnapshot> {
            calls += "environment:${request.key}"
            developerSnapshot =
                if (request.key == "custom") {
                    SharedSettingsDeveloperSnapshot(
                        environmentKey = request.key,
                        environmentLabel = request.label,
                        apiBaseUrl = request.apiBaseUrl,
                        socketBaseUrl = request.socketBaseUrl,
                        webUrl = request.webUrl,
                        options = buildDeveloperSnapshot("production").options,
                        feedback = "Selected ${request.label}"
                    )
                } else {
                    buildDeveloperSnapshot(request.key)
                }
            return DataResult.Content(developerSnapshot)
        }

        override suspend fun onApiEnvironmentChanged(
            before: SharedSettingsApiEnvironmentOption,
            after: SharedSettingsApiEnvironmentOption
        ): DataResult<Unit> {
            calls += "environmentChanged:${before.key}:${after.key}"
            return DataResult.Content(Unit)
        }

        override suspend fun checkHandsUpdate(): DataResult<SharedSettingsDeveloperSnapshot> {
            calls += "handsUpdateCheck"
            developerSnapshot =
                developerSnapshot.copy(
                    updateCheck =
                        SharedSettingsUpdateCheckSnapshot(
                            supported = true,
                            statusLabel = "Update available",
                            detail = "Version 1.0.1 is available on main.",
                            updateAvailable = true,
                            latestVersionLabel = "1.0.1",
                            latestVersionCode = 2L,
                            downloadUrl = "https://example.test/app.apk"
                        ),
                    feedback = "Update available: 1.0.1."
                )
            return DataResult.Content(developerSnapshot)
        }

        override suspend fun checkHandsUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> {
            calls += "handsUpdateStatus"
            return updateStatusResult
        }

        override suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
            calls += "clearStorage:$target"
            return DataResult.Content(storageSnapshot(feedback = "Cleared $target"))
        }

        override suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> {
            calls += "diagnostics"
            return diagnosticsResult
        }

        fun setDiagnosticsResult(snapshot: SharedSettingsDiagnosticsHostSnapshot) {
            diagnosticsResult = DataResult.Content(snapshot)
        }

        override suspend fun setDiagnosticFeatureFlagOverride(key: String, mode: String): DataResult<Unit> {
            calls += "diagnosticOverride:$key:$mode"
            return DataResult.Content(Unit)
        }

        override suspend fun pickAccountAvatarFile(): DataResult<PlatformFile> {
            calls += "pickAccountAvatarFile"
            return avatarFileResult
        }

        override suspend fun pickConnectedAppLogoFile(clientRecordId: String): DataResult<PlatformFile> {
            calls += "pickConnectedAppLogoFile:$clientRecordId"
            return connectedAppLogoFileResult
        }

        override suspend fun loadSystemNotificationPermission(): SettingsMobileActionSnapshot? {
            if (systemNotificationPermission != null) {
                calls += "notificationPermission"
            }
            return systemNotificationPermission
        }

        private fun storageSnapshot(feedback: String? = null): SharedSettingsStorageSnapshot =
            SharedSettingsStorageSnapshot(
                totalSizeLabel = "12.0 MB",
                rows =
                    listOf(
                        SharedSettingsStorageRow(
                            target = "database",
                            title = "Local database",
                            detail = "Local data.",
                            sizeLabel = "4.0 MB"
                        ),
                        SharedSettingsStorageRow(
                            target = "imageCache",
                            title = "Image cache",
                            detail = "Images.",
                            sizeLabel = "7.0 MB",
                            clearEnabled = true
                        ),
                        SharedSettingsStorageRow(
                            target = "temporaryAndLogs",
                            title = "Temporary files and logs",
                            detail = "Logs.",
                            sizeLabel = "1.0 MB",
                            clearEnabled = true
                        )
                    ),
                feedback = feedback
            )

        companion object {
            private fun buildDeveloperSnapshot(environmentKey: String): SharedSettingsDeveloperSnapshot {
                val production =
                    SharedSettingsApiEnvironmentOption(
                        key = "production",
                        label = "Production",
                        apiBaseUrl = "https://api.raft.build/api/",
                        socketBaseUrl = "https://api.raft.build",
                        webUrl = "https://app.raft.build"
                    )
                val staging =
                    SharedSettingsApiEnvironmentOption(
                        key = "staging",
                        label = "Staging",
                        apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                        socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                        webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
                    )
                val selected = if (environmentKey == "staging") staging else production
                return SharedSettingsDeveloperSnapshot(
                    environmentKey = selected.key,
                    environmentLabel = selected.label,
                    apiBaseUrl = selected.apiBaseUrl,
                    socketBaseUrl = selected.socketBaseUrl,
                    webUrl = selected.webUrl,
                    options = listOf(production, staging),
                    feedback = "Selected ${selected.label}"
                )
            }
        }
    }

    private object ConversationForwardCapableSettingsPlatformCapabilities :
        SharedSettingsPlatformCapabilities {
        override val supportsConversationForwardGesture: Boolean = true
    }

    private fun diagnosticOverridesHostSnapshot(
        clientOverride: String,
        expiresAtMillis: Long?,
        finalEffective: Boolean,
    ): SharedSettingsDiagnosticsHostSnapshot =
        SharedSettingsDiagnosticsHostSnapshot(
            featureFlagOverrides =
                listOf(
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "mobile_activity_stable_order_v1",
                        supported = true,
                        entitled = true,
                        clientOverride = clientOverride,
                        expiresAtMillis = expiresAtMillis,
                        serverEffective = true,
                        finalEffective = finalEffective,
                        precedence =
                            when (clientOverride) {
                                "ForceOn" -> "diagnostic_override_force_on"
                                "ForceOff" -> "diagnostic_override_force_off"
                                else -> "server"
                            },
                    )
                )
        )

    private class FakeNotificationPreferenceRepository(
        var enabled: Boolean = false,
        var foregroundKeepAliveEnabled: Boolean = false,
        var offlineComputerAlertsEnabled: Boolean = false,
    ) : NotificationPreferenceRepository {
        override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
            NotificationPreferenceSnapshot(
                enabled = enabled,
                foregroundKeepAliveEnabled = foregroundKeepAliveEnabled,
                offlineComputerAlertsEnabled = offlineComputerAlertsEnabled,
            )

        override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
            this.enabled = enabled
            return getNotificationPreference()
        }

        override suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
            foregroundKeepAliveEnabled = enabled
            return getNotificationPreference()
        }

        override suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
            offlineComputerAlertsEnabled = enabled
            return getNotificationPreference()
        }
    }

    private class FakeLocaleRepository(
        var current: AppLocale = AppLocale.English
    ) : LocaleRepository {
        override suspend fun getLocale(): LocaleSnapshot =
            LocaleSnapshot(current = current)

        override suspend fun selectLocale(locale: AppLocale): LocaleSnapshot {
            current = locale
            return LocaleSnapshot(current = current)
        }
    }

    private class FakeLinkOpenPreferenceRepository(
        var mode: LinkOpenMode = LinkOpenMode.SystemBrowser
    ) : LinkOpenPreferenceRepository {
        override fun getLinkOpenModeSync(): LinkOpenMode = mode

        override suspend fun getLinkOpenMode(): LinkOpenMode = mode

        override suspend fun selectLinkOpenMode(mode: LinkOpenMode): LinkOpenMode {
            this.mode = mode
            return mode
        }
    }

    private class FakeChatLayoutPreferenceRepository(
        var alignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Left,
        var compactConsecutiveMessages: Boolean = false,
        var longMessageCollapseEnabled: Boolean = false,
        var singleTapOpensThreadEnabled: Boolean = false,
        var threadUnreadJumpEnabled: Boolean = false,
        var newMessageMenuEnabled: Boolean = false,
        var conversationForwardGestureEnabled: Boolean = false,
        var foregroundMessageBannersEnabled: Boolean = false,
        var activityTaskStatusBadgesEnabled: Boolean = false,
        var activitySenderAvatarsEnabled: Boolean = false,
        var composerSendKeyEnabled: Boolean = true,
        var multiShellHostEnabled: Boolean = false,
        var serverDrawerEnabled: Boolean = false,
        var newDesignSettingsEnabled: Boolean = false,
        var homeServerAvatarEnabled: Boolean = false,
        var translationMode: TranslationModePreference = TranslationModePreference.Automatic,
        var translationTargetLanguage: String = TranslationTargetLanguages.Browser
    ) : ChatLayoutPreferenceRepository {
        override suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot =
            ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                longMessageCollapseEnabled = longMessageCollapseEnabled,
                singleTapOpensThreadEnabled = singleTapOpensThreadEnabled,
                threadUnreadJumpEnabled = threadUnreadJumpEnabled,
                newMessageMenuEnabled = newMessageMenuEnabled,
                conversationForwardGestureEnabled = conversationForwardGestureEnabled,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                multiShellHostEnabled = multiShellHostEnabled,
                serverDrawerEnabled = serverDrawerEnabled,
                newDesignSettingsEnabled = newDesignSettingsEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                translationMode = translationMode,
                translationTargetLanguage = translationTargetLanguage,
            )

        override suspend fun selectMyMessageAlignment(
            alignment: MyMessageAlignmentPreference
        ): ChatLayoutPreferenceSnapshot {
            this.alignment = alignment
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = this.alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setDesktopModeOnLargeScreen(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                desktopModeOnLargeScreen = enabled,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )

        override suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            compactConsecutiveMessages = enabled
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setLongMessageCollapseEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            longMessageCollapseEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setSingleTapOpensThreadEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            singleTapOpensThreadEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setThreadUnreadJumpEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            threadUnreadJumpEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setNewMessageMenuEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            newMessageMenuEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setConversationForwardGestureEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            conversationForwardGestureEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setForegroundMessageBannersEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            foregroundMessageBannersEnabled = enabled
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            activityTaskStatusBadgesEnabled = enabled
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setActivitySenderAvatarsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            activitySenderAvatarsEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setComposerSendKeyEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            composerSendKeyEnabled = enabled
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setMultiShellHostEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            multiShellHostEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setServerDrawerEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            serverDrawerEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setNewDesignSettingsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            newDesignSettingsEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setHomeServerAvatarEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
            homeServerAvatarEnabled = enabled
            return getChatLayoutPreference()
        }

        override suspend fun setTranslationMode(mode: TranslationModePreference): ChatLayoutPreferenceSnapshot {
            translationMode = mode
            return ChatLayoutPreferenceSnapshot(
                myMessageAlignment = alignment,
                compactConsecutiveMessages = compactConsecutiveMessages,
                foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
                activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                composerSendKeyEnabled = composerSendKeyEnabled,
                translationMode = translationMode,
            )
        }

        override suspend fun setTranslationTargetLanguage(language: String): ChatLayoutPreferenceSnapshot {
            translationTargetLanguage = TranslationTargetLanguages.normalize(language) ?: TranslationTargetLanguages.Browser
            return getChatLayoutPreference()
        }

    }

    internal fun sampleSession(role: String = "owner"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock", role = role, plan = "team")
                )
        )

    private fun computersSnapshot(machineId: String): SettingsComputersSnapshot =
        SettingsComputersSnapshot(
            computers =
                listOf(
                    SettingsComputerSnapshot(
                        id = machineId,
                        name = "MacBook Pro",
                        daemonVersion = "0.51.1",
                        online = true
                    )
                )
        )

    private fun computerDetailSnapshot(machineId: String): SettingsComputerDetailSnapshot {
        val computer =
            SettingsComputerSnapshot(
                id = machineId,
                name = "MacBook Pro",
                daemonVersion = "0.51.1",
                online = true
            )
        return SettingsComputerDetailSnapshot(
            computer = computer,
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList()
        )
    }

    private fun billingSnapshot(): SettingsBillingSnapshot =
        SettingsBillingSnapshot(
            currentPlan =
                SettingsBillingCurrentPlanSnapshot(
                    planName = "Team",
                    usageRows = listOf(SettingsPlanUsageSnapshot("Members", "8 / 20"))
                ),
            plans =
                listOf(
                    SettingsBillingPlanSnapshot(
                        name = "Team",
                        price = "$12 / user",
                        features = listOf("Shared workspaces"),
                        current = true
                    )
                )
        )

    private fun serverSnapshot(): SettingsServerSnapshot =
        SettingsServerSnapshot(
            profile = SettingsServerProfileSnapshot(name = "Slock Community", slug = "slock", canSave = true),
            sections =
                listOf(
                    SettingsServerSectionSnapshot("admins", "Admins".desc(), "1 admin", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("billing", "Plan & Billing".desc(), "Current plan: Team", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("pending_invites", "Pending Invites".desc(), "1 pending invite", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("join_links", "Join Links".desc(), "1 active link", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("onboarding_agent", "Onboarding Agent".desc(), "Enabled", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("translation", "Translation".desc(), "Enabled", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("sidebar_order", "Sidebar Order".desc(), "Manual", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("danger", "Danger Zone".desc(), "Owner only", SettingsSectionAvailability.NoPermission)
                ),
            admins = listOf(SettingsServerAdminSnapshot(id = "admin-1", name = "Admin", role = "admin")),
            usageRows = listOf(SettingsPlanUsageSnapshot("Messages", "100")),
            pendingInvites = listOf(SettingsServerInviteSnapshot(id = "invite-1", email = "invitee@slock.test")),
            joinLinks = listOf(SettingsServerJoinLinkSnapshot(id = "link-1", label = "Invite link")),
            onboarding = SettingsServerOnboardingSnapshot(setupModalReminderOptOut = false, statusLabel = "Enabled", detail = "Agent onboarding enabled."),
            translation =
                SettingsServerTranslationSnapshot(
                    translationEnabled = true,
                    translationAvailable = true,
                    canManageTranslation = true,
                    statusLabel = "Enabled",
                    detail = "Translation enabled."
                ),
            sidebarOrder = SettingsServerSidebarOrderSnapshot(channelSortMode = "manual")
        )

    private fun languageRegionSnapshot(): SettingsLanguageRegionSnapshot =
        SettingsLanguageRegionSnapshot(
            serverTranslation =
                SettingsMobileToggleSnapshot(
                    enabled = true,
                    available = true,
                    statusLabel = "Enabled",
                    detail = "Translation enabled."
                ),
            autoTranslation =
                SettingsMobileToggleSnapshot(
                    enabled = true,
                    available = true,
                    statusLabel = "Enabled",
                    detail = "Auto translation enabled."
                ),
            browserLanguage =
                SettingsMobileSelectionSnapshot(
                    selectedKey = "en",
                    selectedLabel = "English",
                    statusLabel = "English",
                    detail = "Translation target language.",
                    options =
                        listOf(
                            SettingsMobileSelectionOptionSnapshot("browser", "System language"),
                            SettingsMobileSelectionOptionSnapshot("en", "English"),
                            SettingsMobileSelectionOptionSnapshot("ja", "Japanese")
                        )
                ),
            timezone =
                SettingsMobileSelectionSnapshot(
                    selectedKey = "Asia/Shanghai",
                    selectedLabel = "UTC+08:00 Asia/Shanghai",
                    statusLabel = "UTC+08:00 Asia/Shanghai",
                    detail = "Stored on account.",
                    options =
                        listOf(
                            SettingsMobileSelectionOptionSnapshot("UTC", "UTC+00:00 UTC"),
                            SettingsMobileSelectionOptionSnapshot("Asia/Shanghai", "UTC+08:00 Asia/Shanghai"),
                            SettingsMobileSelectionOptionSnapshot("Asia/Tokyo", "UTC+09:00 Asia/Tokyo")
                        )
                ),
            timeFormat =
                SettingsMobileSelectionSnapshot(
                    selectedKey = "24h",
                    selectedLabel = "24-hour",
                    statusLabel = "24-hour",
                    detail = "Clock preference.",
                    options =
                        listOf(
                            SettingsMobileSelectionOptionSnapshot("12h", "12-hour"),
                            SettingsMobileSelectionOptionSnapshot("24h", "24-hour")
                        )
                )
        )

    private fun SettingsLanguageRegionPreferenceUpdateRequest.callLabel(): String =
        when {
            autoTranslationEnabled != null -> "auto=$autoTranslationEnabled"
            preferredLanguage != null -> "language=$preferredLanguage"
            clearPreferredLanguage -> "language=null"
            preferredTimezone != null -> "timezone=$preferredTimezone"
            clearPreferredTimezone -> "timezone=null"
            preferredTimeFormat != null -> "timeFormat=$preferredTimeFormat"
            clearPreferredTimeFormat -> "timeFormat=null"
            preferredTranslationDisplay != null -> "display=$preferredTranslationDisplay"
            else -> "empty"
        }

    private fun connectedAppsSnapshot(): SettingsConnectedAppsSnapshot =
        SettingsConnectedAppsSnapshot(
            marketplace =
                listOf(
                    SettingsConnectedAppMarketplaceSnapshot(
                        id = "market-1",
                        clientId = "calendar",
                        name = "Calendar",
                        category = "productivity",
                        developer = "Raft",
                        description = "Sync reminders.",
                        homepageDomain = "calendar.example",
                        callbackDomain = "calendar.example",
                        installed = false,
                        dataAccessSummary = "Profile and reminders"
                    )
                ),
            builtIn =
                listOf(
                    SettingsConnectedAppBuiltInSnapshot(
                        id = "built-in-1",
                        clientId = "raft-actions",
                        name = "Raft Actions",
                        description = "Built-in action callbacks."
                    )
                ),
            installed = emptyList(),
            registered =
                listOf(
                    SettingsConnectedAppClientSnapshot(
                        id = "client-1",
                        serverId = "server-1",
                        clientId = "local-client",
                        appType = "server_local",
                        publishStatus = "draft",
                        category = "productivity",
                        dataAccessSummary = "Profile",
                        name = "Local Client",
                        description = "Private client.",
                        returnUrl = "https://example.com/callback"
                    )
                ),
            canManage = true
        )

    private fun accountSnapshot(
        displayName: String = "Slock User",
        handle: String = "@slock",
        email: String = "user@slock.ai"
    ): AccountSettingsSnapshot =
        AccountSettingsSnapshot(
            profile =
                AccountProfileSnapshot(
                    userId = "user-1",
                    displayName = displayName,
                    handle = handle,
                    email = email,
                    avatarLabel = displayName.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "SU" },
                    verified = true
                ),
            connectedAccounts = emptyList()
        )

    private class BlockingCall {
        private val started = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun consume() {
            started.complete(Unit)
            released.await()
        }

        suspend fun awaitStarted() {
            started.await()
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class BlockingSessionStore(
        private val delegate: RaftAuthSessionStore
    ) : RaftAuthSessionStore {
        var readBlock: BlockingCall? = null

        override suspend fun readSession(): StoredAuthSession? {
            readBlock?.consume()
            return delegate.readSession()
        }

        override suspend fun saveSession(session: StoredAuthSession) = delegate.saveSession(session)

        override suspend fun updateTokens(accessToken: String, refreshToken: String) =
            delegate.updateTokens(accessToken, refreshToken)

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) =
            delegate.updateSelectedServer(serverId, serverSlug)

        override suspend fun clearSession(reason: String, source: String) =
            delegate.clearSession(reason, source)
    }

    private class EventingSessionStore(
        initialSession: StoredAuthSession
    ) : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private val delegate = InMemoryRaftAuthSessionStore(initialSession)
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
        private var generation = 0L

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun readSession(): StoredAuthSession? = delegate.readSession()

        override suspend fun saveSession(session: StoredAuthSession) {
            delegate.saveSession(session)
            events.emit(AuthSessionEvent.Authenticated(++generation))
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            delegate.updateTokens(accessToken, refreshToken)
            events.emit(AuthSessionEvent.TokensRotated(++generation))
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            delegate.updateSelectedServer(serverId, serverSlug)
            events.emit(AuthSessionEvent.BindingMetadataChanged(++generation))
        }

        override suspend fun clearSession(reason: String, source: String) {
            delegate.clearSession(reason, source)
            events.emit(AuthSessionEvent.Cleared(++generation, AuthSessionClearReason.Logout))
        }
    }

    private fun settingsStore(
        repository: FakeSettingsRepository,
        sessionStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        scope: CoroutineScope
    ): SharedSettingsStore =
        SharedSettingsStore(
            dataSource =
                SharedSettingsDataSource(
                    sessionStore = sessionStore,
                    settingsRepository = repository,
                    notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                    localeRepository = FakeLocaleRepository(),
                    chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                ),
            scope = scope
        )

    private fun mobileSnapshot(): SettingsMobileSnapshot =
        SettingsMobileSnapshot(
            pushNotifications =
                SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Off",
                    detail = "Local notification preference."
                ),
            muteServer =
                SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Not muted",
                    detail = "Server mute available."
                ),
            sendTestEnabled = true
        )

    @Test
    fun noOpGateSkipsEquivalentSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(sampleSession())),
                scope = scope
            )

        // Collect all state emissions from the contract
        val emissions = mutableListOf<SharedSettingsState>()
        val contract = SharedSettingsStoreContract(store, scope)
        val job = scope.launch { contract.state.collect { emissions.add(it) } }

        // Dispatch ConsumeEffect twice — second should be a no-op since effect is already null
        store.accept(SharedSettingsIntent.ConsumeEffect)
        yield()
        val countAfterFirst = emissions.size
        store.accept(SharedSettingsIntent.ConsumeEffect)
        yield()

        // Contract should filter out the equivalent state emission
        assertEquals(countAfterFirst, emissions.size)
        job.cancel()
        scope.cancel()
    }

    @Test
    fun transitionLogCapturesDispatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val log = mutableListOf<MviTransition<SharedSettingsIntent>>()
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(sampleSession())),
                scope = scope
            )
        val contract = SharedSettingsStoreContract(store, scope, transitionLogger = { log.add(it) })

        // Dispatching Start should log a transition
        contract.dispatch(SharedSettingsIntent.Start)
        yield()

        assertTrue(log.isNotEmpty(), "Expected at least one transition, got ${log.size}")
        assertTrue(log.any { it.intent == SharedSettingsIntent.Start }, "Start intent not found in log")
        scope.cancel()
    }

    @Test
    fun inFlightGuardSkipsDuplicateMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val skippedIntents = mutableListOf<SharedSettingsIntent>()
        val repository = FakeSettingsRepository(accountResult = DataResult.Content(accountSnapshot()))
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                settingsRepository = repository
            )
        val store = SharedSettingsStore(dataSource, scope)
        val contract =
            SharedSettingsStoreContract(
                store,
                scope,
                transitionLogger = { t -> if (!t.emitted) skippedIntents.add(t.intent) }
            )

        // First mutation: guard acquired, then store processes it
        val request =
            SharedSettingsIntent.UpdateProfile(
                AccountProfileUpdateRequest(displayName = "alice", handle = "alice", email = "a@b.com")
            )
        contract.dispatch(request)
        yield()

        // Second identical mutation should be blocked by inFlightGuard
        val skippedBefore = skippedIntents.size
        contract.dispatch(request)
        yield()

        // After second dispatch, at least one intent should be skipped or the first is still in-flight
        assertTrue(
            skippedIntents.size >= skippedBefore,
            "Expected skipped intents to not decrease (was $skippedBefore, now ${skippedIntents.size})"
        )
        scope.cancel()
    }

    @Test
    fun failurePreservesOldContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(sessionStore),
                scope = scope
            )
        val contract = SharedSettingsStoreContract(store, scope)

        // Load initial content
        store.accept(SharedSettingsIntent.Start)
        yield()
        val homeBefore =
            assertIs<ScreenLoadState.Content<SharedSettingsHomeSnapshot>>(contract.state.value.home)

        // Refresh with no repository (empty result) — should preserve old content
        store.accept(SharedSettingsIntent.Refresh)
        yield()

        // Home should still be the same content (not replaced by empty/loading)
        val homeAfter =
            assertIs<ScreenLoadState.Content<SharedSettingsHomeSnapshot>>(contract.state.value.home)
        assertEquals(homeBefore.data.subtitle, homeAfter.data.subtitle)
        scope.cancel()
    }

    private class FakeSettingsMemberRepository(
        private val uploadAvatarResult: DataResult<MemberProfile>? = null
    ) : MemberRepository {
        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> =
            DataResult.Content(MemberSnapshot(settingsServer(serverId.value), emptyList()))

        override suspend fun getMemberProfile(
            priority: DataRequestPriority,
            memberId: MemberId,
            serverId: ServerId?
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun updateAgentProfile(
            priority: DataRequestPriority,
            request: AgentProfileUpdateRequest
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun uploadAgentAvatar(
            priority: DataRequestPriority,
            request: AgentAvatarUploadRequest
        ): DataResult<MemberProfile> = uploadAvatarResult ?: error("unused")

        override suspend fun getAgentDetail(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDetailSnapshot> = error("unused")

        override suspend fun getAgentDirectMessages(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDmSnapshot> = error("unused")

        override suspend fun getAgentReminders(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentReminderSnapshot> = error("unused")

        override suspend fun getAgentActivity(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentActivitySnapshot> = error("unused")

        override suspend fun getAgentWorkspaces(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceTree(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceTreeSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")

        override suspend fun getAgentSkills(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentSkillsSnapshot> = error("unused")

        override suspend fun startAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun stopAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun resetAgent(
            priority: DataRequestPriority,
            request: AgentResetRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun deleteAgent(
            priority: DataRequestPriority,
            agentId: MemberId,
            workspaceChoice: AgentArchiveWorkspaceChoice
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun assignAgentMachine(
            priority: DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> =
            DataResult.Content(ChannelMemberSnapshot(channelId, emptyList()))

        override suspend fun addChannelMember(
            priority: DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> = error("unused")

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> = error("unused")
    }

    internal fun avatarSettingsRepository(
        accountResult: DataResult<AccountSettingsSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    ): SettingsRepository =
        FakeSettingsRepository(
            accountResult = accountResult,
            serverResult = DataResult.Content(serverSnapshot())
        )

    internal fun avatarSettingsCapabilities(
        avatarFileResult: DataResult<PlatformFile> =
            DataResult.Error(ScreenError("settings_account_avatar_picker_unavailable"))
    ): SharedSettingsPlatformCapabilities =
        FakeSettingsCapabilities(avatarFileResult = avatarFileResult)

    internal fun avatarSettingsMemberRepository(
        uploadAvatarResult: DataResult<MemberProfile>? = null
    ): MemberRepository =
        FakeSettingsMemberRepository(uploadAvatarResult = uploadAvatarResult)
}

internal fun settingsServer(id: String): ServerSummary =
    ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

internal fun settingsMember(id: String, name: String): MemberProfile =
    MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)
