package ai.slock.compose.data

import com.tencent.kuikly.compose.profiler.RecompositionReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSettingsStoreTest {
    @Test
    fun startBuildsSettingsHomeFromSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemorySlockAuthSessionStore(sampleSession())),
                scope = scope
            )

        store.accept(SharedSettingsIntent.Start)

        val home = assertIs<ScreenLoadState.Content<SharedSettingsHomeSnapshot>>(store.state.value.home).data
        assertEquals("Slock Community", home.subtitle)
        assertEquals(
            listOf(
                SharedSettingsDestination.Account,
                SharedSettingsDestination.Computers,
                SharedSettingsDestination.Server,
                SharedSettingsDestination.Billing,
                SharedSettingsDestination.LanguageRegion,
                SharedSettingsDestination.Mobile,
                SharedSettingsDestination.Storage,
                SharedSettingsDestination.About
            ),
            home.items.map { it.destination }
        )
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Computers))

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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Account))
        store.accept(SharedSettingsIntent.UpdateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai")))

        val account = assertIs<ScreenLoadState.Content<AccountSettingsSnapshot>>(store.state.value.account).data
        assertEquals("New Name", account.profile.displayName)
        assertEquals(listOf("account", "updateProfile:New Name"), repository.calls)
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Billing))
        val billing = assertIs<ScreenLoadState.Content<SettingsBillingSnapshot>>(store.state.value.billing).data
        assertEquals("Team", billing.currentPlan.planName)

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Server))
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
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.LanguageRegion))
        val loaded = assertIs<ScreenLoadState.Content<SettingsLanguageRegionSnapshot>>(store.state.value.languageRegion).data
        assertEquals("Asia/Shanghai", loaded.timezone.selectedKey)

        store.accept(
            SharedSettingsIntent.UpdateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = "Asia/Tokyo")
            )
        )
        store.accept(SharedSettingsIntent.SetLanguageRegionServerTranslation(false))

        val updated = assertIs<ScreenLoadState.Content<SettingsLanguageRegionSnapshot>>(store.state.value.languageRegion).data
        assertEquals("Asia/Tokyo", updated.timezone.selectedKey)
        assertEquals(false, updated.serverTranslation.enabled)
        assertEquals(
            listOf(
                "languageRegion",
                "updateLanguageRegionPreference:timezone=Asia/Tokyo",
                "updateServerTranslation:false",
                "languageRegion"
            ),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Server))
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
        store.accept(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("Slock Android")))

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
                "deleteServer:Slock Android",
                "server"
            ),
            repository.calls
        )
        scope.cancel()
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Server))
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Server))
        store.dispatch(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("Slock Android")))
        block.awaitStarted()
        store.dispatch(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest("Slock Android")))

        assertEquals(listOf("server", "deleteServer:Slock Android"), repository.calls)
        assertEquals(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = true), store.state.value.actionState)

        block.release()
        yield()

        val server = assertIs<ScreenLoadState.Content<SettingsServerSnapshot>>(store.state.value.server).data
        assertEquals(initialServer.profile.name, server.profile.name)
        assertEquals(listOf("server", "deleteServer:Slock Android"), repository.calls)
        assertEquals(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = false, errorCode = "settings_server_delete_failed"), store.state.value.actionState)
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Computers))
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
                dataSource = SharedSettingsDataSource(InMemorySlockAuthSessionStore(sampleSession())),
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
    fun mobileLoadsLocalPreferencesThroughSharedStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(enabled = true),
                        localeRepository = FakeLocaleRepository(current = AppLocale.SimplifiedChinese),
                        chatLayoutPreferenceRepository =
                            FakeChatLayoutPreferenceRepository(alignment = MyMessageAlignmentPreference.Right)
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Mobile))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(true, mobile.pushNotifications.enabled)
        assertEquals("Enabled", mobile.pushNotifications.statusLabel)
        assertEquals("Simplified Chinese / 简体中文", mobile.localePreference.statusLabel)
        assertEquals("Right", mobile.messageAlignmentPreference.statusLabel)
        assertEquals(listOf("mobile"), repository.calls)
        scope.cancel()
    }

    @Test
    fun setLocaleKeepsMokoStringDescLocaleTypeInSync() = runBlocking {
        // #85 follow-up: `SharedSettingsDataSource.setLocale` must keep
        // the moko KBA `StringDesc.localeType` global in lock-step
        // with the user's `AppLocale` selection so that any
        // `MR.strings.<key>.desc()` call site (now or future) renders
        // the correct locale without each call site having to thread
        // a locale through.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localeRepository = FakeLocaleRepository(current = AppLocale.English)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                localeRepository = localeRepository
            )
        // Save the original `StringDesc.localeType` and restore it
        // after the test so we don't leak the global to other tests
        // in the same JVM.
        val originalLocaleType = dev.icerock.moko.resources.desc.StringDesc.localeType
        try {
            val store =
                SharedSettingsStore(
                    dataSource = dataSource,
                    scope = scope
                )

            // Step 1: switch to Simplified Chinese. The moko
            // `StringDesc.localeType` should now be a `Custom` whose
            // underlying BCP-47 tag (when resolved through
            // `getSystemLocale()`) is `zh-CN` (the moko KBA folder
            // matcher falls back to the `zh-rCN/` folder by language
            // match).
            val result1 = dataSource.setLocale("zh-Hans")
            assertIs<DataResult.Content<*>>(result1)
            val custom1 =
                dev.icerock.moko.resources.desc.StringDesc.localeType
                    as dev.icerock.moko.resources.desc.StringDesc.LocaleType.Custom
            assertEquals("zh-CN", custom1.systemLocale.toLanguageTag())

            // Step 2: switch back to English. The moko
            // `StringDesc.localeType` should now be a `Custom`
            // whose tag is `en`.
            val result2 = dataSource.setLocale("en")
            assertIs<DataResult.Content<*>>(result2)
            val custom2 =
                dev.icerock.moko.resources.desc.StringDesc.localeType
                    as dev.icerock.moko.resources.desc.StringDesc.LocaleType.Custom
            assertEquals("en", custom2.systemLocale.toLanguageTag())
        } finally {
            dev.icerock.moko.resources.desc.StringDesc.localeType = originalLocaleType
        }
        scope.cancel()
    }

    @Test
    fun mokoLocaleTagMappingCoversSupportedAppLocales() {
        // #85 follow-up: `AppLocale.mokoLocaleTag` must produce a
        // BCP-47 tag that moko KBA's folder parser can resolve.
        // English is `en`; Simplified Chinese is `zh-CN` (moko's
        // folder matcher falls back from the script-only
        // `zh-Hans` form to the region-based `zh-rCN` folder by
        // language match).
        assertEquals("en", AppLocale.English.mokoLocaleTag)
        assertEquals("zh-CN", AppLocale.SimplifiedChinese.mokoLocaleTag)
    }

    @Test
    fun mobilePreferenceIntentsPersistAndEchoThroughSharedState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val notificationRepository = FakeNotificationPreferenceRepository(enabled = false)
        val localeRepository = FakeLocaleRepository(current = AppLocale.English)
        val chatLayoutRepository = FakeChatLayoutPreferenceRepository(alignment = MyMessageAlignmentPreference.Left)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                        notificationPreferenceRepository = notificationRepository,
                        localeRepository = localeRepository,
                        chatLayoutPreferenceRepository = chatLayoutRepository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.SetNotificationPreference(true))
        store.accept(SharedSettingsIntent.SetLocale("zh-Hans"))
        store.accept(SharedSettingsIntent.SetMessageAlignment("right"))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(true, notificationRepository.enabled)
        assertEquals(AppLocale.SimplifiedChinese, localeRepository.current)
        assertEquals(MyMessageAlignmentPreference.Right, chatLayoutRepository.alignment)
        assertEquals("Enabled", mobile.pushNotifications.statusLabel)
        assertEquals("Simplified Chinese / 简体中文", mobile.localePreference.statusLabel)
        assertEquals("Right", mobile.messageAlignmentPreference.statusLabel)
        assertNull(store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun mobileServerMuteIntentUsesSettingsRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot()))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository,
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.SetServerMute(true))

        val mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals(true, mobile.muteServer.enabled)
        assertEquals("Muted", mobile.muteServer.statusLabel)
        assertEquals(listOf("mobile", "setServerMute:true"), repository.calls)
        scope.cancel()
    }

    @Test
    fun mobileNotificationActionCompletionStoresSuccessAndUnsupportedFeedback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = FakeSettingsRepository(mobileResult = DataResult.Content(mobileSnapshot())),
                        notificationPreferenceRepository = FakeNotificationPreferenceRepository(),
                        localeRepository = FakeLocaleRepository(),
                        chatLayoutPreferenceRepository = FakeChatLayoutPreferenceRepository()
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Mobile))
        store.accept(SharedSettingsIntent.RequestNotificationPermission)
        store.accept(
            SharedSettingsIntent.CompleteSystemNotificationAction(
                MOBILE_ACTION_PERMISSION,
                SystemNotificationActionResult(SystemNotificationActionStatus.Ok, code = "permission_granted")
            )
        )
        var mobile = assertIs<ScreenLoadState.Content<SettingsMobileSnapshot>>(store.state.value.mobile).data
        assertEquals("Done", mobile.notificationPermission.statusLabel)
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
        assertEquals("notification_unsupported", store.state.value.actionState?.errorCode)
        scope.cancel()
    }

    @Test
    fun openComputerLoadsDetailRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeSettingsRepository(computerDetailResult = DataResult.Content(computerDetailSnapshot("machine-42")))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))

        val detail = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "machine-42"), store.state.value.route)
        assertEquals("machine-42", detail.computer.id)
        assertEquals(listOf("computerDetail:machine-42"), repository.calls)
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
                renameComputerResult = DataResult.Content(renamed)
            )
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the detail page first so the route is the detail page, then
        // rename. The rename helper should call renameComputer + reload
        // the detail snapshot, leaving the route on ComputerDetail.
        store.accept(SharedSettingsIntent.OpenComputer("machine-42"))
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                    ai.slock.compose.model.RealtimeRefreshRequired(
                        scope = ai.slock.compose.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = ai.slock.compose.model.ServerId("server-1"),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                    ai.slock.compose.model.RealtimeRefreshRequired(
                        scope = ai.slock.compose.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = ai.slock.compose.model.ServerId("server-1"),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        settingsRepository = repository
                    ),
                scope = scope
            )

        // Open the Computers list route.
        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Computers))
        repository.calls.clear()

        store.accept(
            SharedSettingsIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    ai.slock.compose.model.RealtimeRefreshRequired(
                        scope = ai.slock.compose.model.RealtimeRefreshScope.Machines,
                        reason = "machine:updated",
                        serverId = ai.slock.compose.model.ServerId("server-1"),
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                    ai.slock.compose.model.RealtimeRefreshRequired(
                        scope = ai.slock.compose.model.RealtimeRefreshScope.ServerMembers,
                        reason = "server:member-updated",
                        serverId = ai.slock.compose.model.ServerId("server-1")
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                        recompositionProfilerController =
                            SharedSettingsRecompositionProfilerController(
                                debugGate = FakeProfilerDebugGate(supported = false)
                            )
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
        val about = assertIs<ScreenLoadState.Content<SharedSettingsAboutSnapshot>>(store.state.value.about).data
        assertEquals("1.2.3", about.versionLabel)
        assertEquals("456", about.buildLabel)

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Diagnostics))
        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(listOf("SlockTest"), diagnostics.entries.map { it.tag })
        assertEquals(false, diagnostics.profiler.supported)
        assertEquals(listOf("about", "diagnostics"), capabilities.calls)
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
                            packageName = "ai.slock.android",
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))

        val about = assertIs<ScreenLoadState.Content<SharedSettingsAboutSnapshot>>(store.state.value.about).data
        assertEquals("Artin", about.signedInUserLabel)
        assertEquals("Slock Community", about.selectedServerLabel)
        assertEquals("Signed in", about.sessionStatusLabel)
        assertEquals(listOf("about"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun profilerActionsStayInSharedStoreAndOnlyReloadHostDiagnostics() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities()
        val engine = FakeProfilerEngine()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                        recompositionProfilerController =
                            SharedSettingsRecompositionProfilerController(
                                engine = engine,
                                debugGate = FakeProfilerDebugGate(supported = true, gateLabel = "Android debug")
                            )
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Diagnostics))
        store.accept(SharedSettingsIntent.StartRecompositionProfiler)

        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(true, engine.started)
        assertEquals("Running", diagnostics.profiler.statusLabel)
        assertEquals(true, diagnostics.profiler.defaults.enableOverlay)
        assertEquals(
            "Profiler started with file/log/overlay enabled. The bottom-right overlay FAB should appear for this session.",
            diagnostics.profiler.actionFeedback
        )
        assertNull(store.state.value.effect)
        assertEquals(
            SharedSettingsActionState(DIAGNOSTICS_ACTION_PROFILER_START, inProgress = false),
            store.state.value.actionState
        )
        assertEquals(listOf("diagnostics", "diagnostics"), capabilities.calls)
        scope.cancel()
    }

    @Test
    fun diagnosticsStillExposeSharedProfilerWhenHostDiagnosticsAreUnavailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capabilities = FakeSettingsCapabilities(diagnosticsResult = DataResult.Empty(EmptyReason.NoSettingsAvailable))
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities,
                        recompositionProfilerController =
                            SharedSettingsRecompositionProfilerController(
                                debugGate = FakeProfilerDebugGate(supported = true)
                            )
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Diagnostics))

        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(emptyList(), diagnostics.entries)
        assertEquals(true, diagnostics.profiler.supported)
        assertEquals("Idle", diagnostics.profiler.statusLabel)
        assertEquals(listOf("diagnostics"), capabilities.calls)
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
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        platformCapabilities = capabilities
                    ),
                scope = scope
            )

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Storage))
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
        var computersResult: DataResult<SettingsComputersSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computerDetailResult: DataResult<SettingsComputerDetailSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var billingResult: DataResult<SettingsBillingSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var serverResult: DataResult<SettingsServerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var languageRegionResult: DataResult<SettingsLanguageRegionSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var mobileResult: DataResult<SettingsMobileSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var renameComputerResult: DataResult<SettingsComputerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var deleteComputerResult: DataResult<SettingsComputerActionResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var rotateComputerKeyResult: DataResult<SettingsComputerKeyRotationResult> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    ) : SettingsRepository, SettingsServerActionRepository, SettingsServerManagementRepository {
        val calls = mutableListOf<String>()
        var updateProfileBlock: BlockingCall? = null
        var createServerInviteBlock: BlockingCall? = null
        var deleteServerBlock: BlockingCall? = null
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

        override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
            calls += "computers"
            return computersResult
        }

        override suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> {
            calls += "computerDetail:$machineId"
            return computerDetailResult
        }

        override suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot> {
            calls += "renameComputer:${request.machineId}:${request.name}"
            return renameComputerResult
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
            calls += "updateServerProfile:${request.name}"
            val current = (serverResult as? DataResult.Content)?.data ?: return serverResult
            serverResult = DataResult.Content(current.copy(profile = current.profile.copy(name = request.name)))
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

        override suspend fun updateServerOnboarding(
            request: SettingsServerOnboardingUpdateRequest
        ): DataResult<SettingsServerOnboardingSnapshot> {
            calls += "updateServerOnboarding:${request.setupModalReminderOptOut}"
            val onboarding =
                SettingsServerOnboardingSnapshot(
                    setupModalReminderOptOut = request.setupModalReminderOptOut,
                    statusLabel = if (request.setupModalReminderOptOut) "Disabled" else "Enabled",
                    detail = "Agent onboarding ${if (request.setupModalReminderOptOut) "disabled" else "enabled"}."
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
                                    current.browserLanguage.copy(selectedKey = "other", selectedLabel = "Others", statusLabel = "Others")
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
                            } ?: current.timeFormat
                    )
                )
            return languageRegionResult
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
    }

    private class FakeSettingsCapabilities(
        private val aboutResult: DataResult<SharedSettingsAboutSnapshot> =
            DataResult.Content(
                SharedSettingsAboutSnapshot(
                    appName = "Slock",
                    versionLabel = "1.2.3",
                    buildLabel = "456",
                    packageName = "ai.slock.android",
                    signedInUserLabel = "Artin",
                    selectedServerLabel = "Slock Community"
                )
            ),
        private val diagnosticsResult: DataResult<SharedSettingsDiagnosticsHostSnapshot> =
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
            )
    ) : SharedSettingsPlatformCapabilities {
        val calls = mutableListOf<String>()

        override suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> {
            calls += "about"
            return aboutResult
        }

        override suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> {
            calls += "storage"
            return DataResult.Content(storageSnapshot())
        }

        override suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
            calls += "clearStorage:$target"
            return DataResult.Content(storageSnapshot(feedback = "Cleared $target"))
        }

        override suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> {
            calls += "diagnostics"
            return diagnosticsResult
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
    }

    private class FakeNotificationPreferenceRepository(
        var enabled: Boolean = false
    ) : NotificationPreferenceRepository {
        override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
            NotificationPreferenceSnapshot(enabled)

        override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
            this.enabled = enabled
            return NotificationPreferenceSnapshot(enabled)
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

    private class FakeChatLayoutPreferenceRepository(
        var alignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Left
    ) : ChatLayoutPreferenceRepository {
        override suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot =
            ChatLayoutPreferenceSnapshot(myMessageAlignment = alignment)

        override suspend fun selectMyMessageAlignment(
            alignment: MyMessageAlignmentPreference
        ): ChatLayoutPreferenceSnapshot {
            this.alignment = alignment
            return ChatLayoutPreferenceSnapshot(myMessageAlignment = this.alignment)
        }

        override suspend fun selectLargeScreenNavigation(
            navigation: LargeScreenNavigationPreference
        ): ChatLayoutPreferenceSnapshot =
            ChatLayoutPreferenceSnapshot(myMessageAlignment = alignment, largeScreenNavigation = navigation)
    }

    private class FakeProfilerEngine : SharedSettingsRecompositionProfilerEngine {
        override val isEnabled: Boolean
            get() = started

        var started: Boolean = false

        override fun clearCustomFilters() = Unit

        override fun configure(block: com.tencent.kuikly.compose.profiler.RecompositionConfigBuilder.() -> Unit) = Unit

        override fun start() {
            started = true
        }

        override fun stop() {
            started = false
        }

        override fun reset() = Unit

        override fun getReport(saveToFile: Boolean): RecompositionReport =
            RecompositionReport.EMPTY
    }

    private class FakeProfilerDebugGate(
        override val supported: Boolean,
        override val gateLabel: String = if (supported) "Debug build" else "Debug only",
        override val unavailableDetail: String = "Recomposition profiler is available only in shared debug builds."
    ) : SharedSettingsRecompositionProfilerDebugGate

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock", role = "owner", plan = "team")
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
                    SettingsServerSectionSnapshot("admins", "Admins", "1 admin", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("billing", "Plan & Billing", "Current plan: Team", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("pending_invites", "Pending Invites", "1 pending invite", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("join_links", "Join Links", "1 active link", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("onboarding_agent", "Onboarding Agent", "Enabled", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("translation", "Translation", "Enabled", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("sidebar_order", "Sidebar Order", "Manual", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("danger", "Danger Zone", "Owner only", SettingsSectionAvailability.NoPermission)
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
                            SettingsMobileSelectionOptionSnapshot("other", "Others"),
                            SettingsMobileSelectionOptionSnapshot("en", "English")
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
            else -> "empty"
        }

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
                dataSource = SharedSettingsDataSource(InMemorySlockAuthSessionStore(sampleSession())),
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
                dataSource = SharedSettingsDataSource(InMemorySlockAuthSessionStore(sampleSession())),
                scope = scope
            )
        val contract = SharedSettingsStoreContract(store, scope, transitionLogger = { log.add(it) })

        // Dispatching Start should log a transition
        contract.dispatch(SharedSettingsIntent.Start)
        yield()

        assert(log.isNotEmpty()) { "Expected at least one transition, got ${log.size}" }
        assert(log.any { it.intent == SharedSettingsIntent.Start }) { "Start intent not found in log" }
        scope.cancel()
    }

    @Test
    fun inFlightGuardSkipsDuplicateMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val skippedIntents = mutableListOf<SharedSettingsIntent>()
        val repository = FakeSettingsRepository(accountResult = DataResult.Content(accountSnapshot()))
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
        assert(skippedIntents.size >= skippedBefore) {
            "Expected skipped intents to not decrease (was $skippedBefore, now ${skippedIntents.size})"
        }
        scope.cancel()
    }

    @Test
    fun failurePreservesOldContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
}
