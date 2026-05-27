package ai.slock.compose.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    fun aboutAndDiagnosticsLoadThroughPlatformCapabilities() = runBlocking {
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

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
        val about = assertIs<ScreenLoadState.Content<SharedSettingsAboutSnapshot>>(store.state.value.about).data
        assertEquals("1.2.3", about.versionLabel)
        assertEquals("456", about.buildLabel)

        store.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Diagnostics))
        val diagnostics = assertIs<ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>>(store.state.value.diagnostics).data
        assertEquals(listOf("SlockTest"), diagnostics.entries.map { it.tag })
        assertEquals(listOf("about", "diagnostics"), capabilities.calls)
        scope.cancel()
    }

    private class FakeSettingsRepository(
        var accountResult: DataResult<AccountSettingsSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computersResult: DataResult<SettingsComputersSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var computerDetailResult: DataResult<SettingsComputerDetailSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var billingResult: DataResult<SettingsBillingSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var serverResult: DataResult<SettingsServerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        var mobileResult: DataResult<SettingsMobileSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    ) : SettingsRepository {
        val calls = mutableListOf<String>()

        override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
            calls += "account"
            return accountResult
        }

        override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> =
            accountResult

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

        override suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

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

        override suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot> =
            serverResult

        override suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
            calls += "mobile"
            return mobileResult
        }
    }

    private class FakeSettingsCapabilities : SharedSettingsPlatformCapabilities {
        val calls = mutableListOf<String>()

        override suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> {
            calls += "about"
            return DataResult.Content(
                SharedSettingsAboutSnapshot(
                    appName = "Slock",
                    versionLabel = "1.2.3",
                    buildLabel = "456",
                    packageName = "ai.slock.android",
                    signedInUserLabel = "Artin",
                    selectedServerLabel = "Slock Community"
                )
            )
        }

        override suspend fun loadDiagnostics(): DataResult<SharedSettingsDiagnosticsSnapshot> {
            calls += "diagnostics"
            return DataResult.Content(
                SharedSettingsDiagnosticsSnapshot(
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
        }
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
                    available = false,
                    statusLabel = "Unavailable",
                    detail = "Server mute unavailable."
                ),
            sendTestEnabled = true
        )
}
