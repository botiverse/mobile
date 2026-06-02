package ai.slock.compose.data

import com.tencent.kuikly.compose.profiler.RecompositionReport
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
    fun accountSaveIntentUsesSettingsRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeSettingsRepository(
                accountResult =
                    DataResult.Content(
                        AccountSettingsSnapshot(
                            profile =
                                AccountProfileSnapshot(
                                    userId = "user-1",
                                    displayName = "New Name",
                                    handle = "@new",
                                    email = "new@slock.ai",
                                    avatarLabel = "NN",
                                    verified = true
                                ),
                            connectedAccounts = emptyList()
                        )
                    )
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
        assertEquals(listOf("billing", "pending_invites", "join_links", "onboarding_agent"), server.sections.map { it.key })
        assertEquals(listOf("billing", "server"), repository.calls)
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
        var mobileResult: DataResult<SettingsMobileSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    ) : SettingsRepository, SettingsServerActionRepository {
        val calls = mutableListOf<String>()

        override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
            calls += "account"
            return accountResult
        }

        override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
            calls += "updateProfile:${request.displayName}"
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

        override suspend fun revokeServerInvite(inviteId: String): DataResult<SettingsServerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun createServerJoinLink(
            request: SettingsServerJoinLinkCreateRequest
        ): DataResult<SettingsServerJoinLinkSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun revokeServerJoinLink(linkId: String): DataResult<SettingsServerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> =
            DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
            calls += "mobile"
            return mobileResult
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
                    SettingsServerSectionSnapshot("billing", "Plan & Billing", "Current plan: Team", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("pending_invites", "Pending Invites", "1 pending invite", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("join_links", "Join Links", "1 active link", SettingsSectionAvailability.Available),
                    SettingsServerSectionSnapshot("onboarding_agent", "Onboarding Agent", "Enabled", SettingsSectionAvailability.Available)
                ),
            admins = listOf(SettingsServerAdminSnapshot(id = "user-admin", name = "Admin", role = "owner")),
            usageRows = listOf(SettingsPlanUsageSnapshot("Messages", "100")),
            pendingInvites = listOf(SettingsServerInviteSnapshot(id = "invite-1", email = "invitee@slock.test")),
            joinLinks = listOf(SettingsServerJoinLinkSnapshot(id = "link-1", label = "Invite link")),
            onboarding = SettingsServerOnboardingSnapshot(setupModalReminderOptOut = false, statusLabel = "Enabled", detail = "Agent onboarding enabled.")
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
}
