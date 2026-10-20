package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeDataSourceTest {
    @Test
    fun homePermissionBoundaryRetainsExactChannelsErrorOn401And403() = runBlocking {
        listOf(401, 403).forEach { status ->
            val exactReason = "  home-$status raw reason  "
            val api =
                object : RaftHomeApi {
                    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
                        HomeNetworkResponse(
                            code = status,
                            bodyText = null,
                            errorBodyText = """{"error":"$exactReason"}"""
                        )

                    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
                        HomeNetworkResponse(code = 200, bodyText = "[]")

                    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
                        HomeNetworkResponse(code = 200, bodyText = "{}")

                    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
                        HomeNetworkResponse(code = 200, bodyText = "[]")
                }

            val result = RaftHomeApiClient(api).homePayload(ServerId("server-1"), DataRequestPriority.Visible)

            val blocker = assertIs<DataResult.NoPermission>(result).blocker
            assertEquals("home_http_$status", blocker.code)
            assertEquals(exactReason, blocker.serverMessage)
        }
    }

    @Test
    fun loadHomeFetchesApiWithoutWritingLocalHomeSnapshot() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                parseHome = { payload, session ->
                    assertEquals(emptyList(), payload.channels)
                    assertEquals(emptyList(), payload.directMessages)
                    assertEquals(emptyMap(), payload.unreadCounts)
                    assertEquals(emptyList(), payload.serverUnreadSummary)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadHome()

        assertEquals(1, (result as DataResult.Content).data.channels.size)
        // loadHome no longer writes a durable home snapshot: the home blob cache
        // was removed in fb845a19 ("fix(home): remove home snapshot blob cache").
        assertNull(localStore.readHome(ServerId("server-1")))
    }

    @Test
    fun loadHomeRequestsCoreHomeSectionsConcurrently() = runBlocking {
        val api = ConcurrentHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = api,
                parseHome = { payload, _ ->
                    DataResult.Content(sampleHome(payload.serverId))
                }
            )

        val result = withTimeout(1_000L) { source.loadHome() }

        assertTrue(result is DataResult.Content)
        assertEquals(
            setOf("channels", "direct_messages", "channel_unread_counts", "server_unread_summary"),
            api.calls.toSet()
        )
    }

    @Test
    fun loadNotificationsDoesNotRequestActiveAnnouncements() = runBlocking {
        val computersApi = ConcurrentComputersApi(CompletableDeferred(Unit))
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
                api = FakeHomeApi(),
                announcementsApi =
                    object : RaftAnnouncementsRemindersApi {
                        override suspend fun activeAnnouncements(): HomeNetworkResponse =
                            error("Home notification center must not request account announcements")

                        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse =
                            error("unused")

                        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
                            error("unused")
                    },
                computersApi = computersApi,
                notificationPreferenceRepository = notificationPreferences(offlineComputerAlertsEnabled = true),
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = withTimeout(1_000L) { source.loadNotifications() }

        val notifications = (result as DataResult.Content).data
        assertEquals(listOf("machine-offline:machine-1"), notifications.map { it.id })
    }

    @Test
    fun offlineComputerAlertsDefaultOffWithoutSuppressingOutdatedAlerts() = runBlocking {
        val machinesBody =
            """
            {
              "latestDaemonVersion": "0.63.7",
              "machines": [
                {
                  "id": "machine-offline",
                  "name": "Offline computer",
                  "status": "offline",
                  "statusVersion": 1
                },
                {
                  "id": "machine-outdated",
                  "name": "Outdated computer",
                  "status": "online",
                  "statusVersion": 1,
                  "daemonVersion": "0.62.0"
                }
              ]
            }
            """.trimIndent()

        suspend fun notifications(offlineComputerAlertsEnabled: Boolean?): List<SharedHomeNotification> {
            val source =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
                    api = FakeHomeApi(),
                    computersApi =
                        ConcurrentComputersApi(
                            announcementsStarted = CompletableDeferred(Unit),
                            bodyText = machinesBody,
                        ),
                    notificationPreferenceRepository =
                        offlineComputerAlertsEnabled?.let(::notificationPreferences),
                    parseHome = { _, session ->
                        DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                    },
                )
            return (source.loadNotifications() as DataResult.Content).data
        }

        assertEquals(
            listOf("machine-outdated:0.63.7:machine-outdated"),
            notifications(offlineComputerAlertsEnabled = null).map { it.id },
        )
        assertEquals(
            listOf(
                "machine-offline:machine-offline",
                "machine-outdated:0.63.7:machine-outdated",
            ),
            notifications(offlineComputerAlertsEnabled = true).map { it.id },
        )
    }

    @Test
    fun outdatedDaemonAlertsExcludeManagedComputersButKeepRawDaemons() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
                api = FakeHomeApi(),
                computersApi =
                    ConcurrentComputersApi(
                        announcementsStarted = CompletableDeferred(Unit),
                        bodyText =
                            """
                            {
                              "latestDaemonVersion": "1.0.17",
                              "machines": [
                                {
                                  "id": "managed-computer",
                                  "name": "Managed computer",
                                  "status": "online",
                                  "statusVersion": 1,
                                  "daemonVersion": "1.0.16",
                                  "isComputer": true
                                },
                                {
                                  "id": "raw-daemon",
                                  "name": "Raw daemon",
                                  "status": "online",
                                  "statusVersion": 1,
                                  "daemonVersion": "1.0.16",
                                  "isComputer": false
                                }
                              ]
                            }
                            """.trimIndent(),
                    ),
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                },
            )

        val notifications = (source.loadNotifications() as DataResult.Content).data

        val notification = notifications.single()
        assertEquals("machine-outdated:1.0.17:raw-daemon", notification.id)
        assertEquals("Raw daemon is running an outdated daemon", notification.title)
        assertEquals("raw-daemon", notification.targetId)
    }

    @Test
    fun machineNotificationsRequireManageMachinesPermissionWithoutHidingAppUpdates() = runBlocking {
        val computersApi = RejectingComputersApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "member")),
                api = FakeHomeApi(),
                computersApi = computersApi,
                appUpdateStatusProvider = {
                    DataResult.Content(
                        SharedSettingsUpdateCheckSnapshot(
                            supported = true,
                            updateAvailable = true,
                            currentVersionCode = 1L,
                            latestVersionCode = 2L
                        )
                    )
                },
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val notifications = (source.loadNotifications() as DataResult.Content).data

        assertEquals(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), notifications.map { it.id })
        assertEquals(0, computersApi.machineCalls)
    }

    @Test
    fun loadNotificationsAddsAppUpdateWhenNewerHandsBuildExists() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                appUpdateStatusProvider = {
                    DataResult.Content(
                        SharedSettingsUpdateCheckSnapshot(
                            supported = true,
                            updateAvailable = true,
                            currentVersionLabel = "1.0.0",
                            currentVersionCode = 1_000_000L,
                            latestVersionLabel = "1.0.1",
                            latestVersionCode = 1_000_100L,
                            downloadUrl = "https://example.test/raft.apk"
                        )
                    )
                },
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadNotifications()

        val notification = (result as DataResult.Content).data.single()
        assertEquals(APP_UPDATE_HOME_NOTIFICATION_ID, notification.id)
        assertEquals("App update available", notification.title)
        assertEquals("Current version 1.0.0. Latest version 1.0.1 (1000100).", notification.body)
        assertEquals("Download", notification.actionLabel)
        assertTrue(notification.titleDesc != null)
        assertTrue(notification.bodyDesc != null)
        assertTrue(notification.actionLabelDesc != null)
        assertEquals(SharedHomeNotificationKind.Info, notification.kind)
        assertEquals(SharedHomeNotificationIcon.CircleArrowUp, notification.icon)
    }

    @Test
    fun loadNotificationsSkipsUnsupportedAppUpdateEvenWhenNativeOfferIsPresent() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                appUpdateStatusProvider = {
                    // Play builds report the capability as unsupported and must
                    // never turn a stale/erroneous offer into a Home banner.
                    DataResult.Content(
                        SharedSettingsUpdateCheckSnapshot(
                            supported = false,
                            updateAvailable = true,
                            currentVersionCode = 1_000_000L,
                            latestVersionCode = 1_000_100L
                        )
                    )
                },
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadNotifications()

        assertEquals(emptyList(), (result as DataResult.Content).data)
    }

    @Test
    fun appUpdateNotificationUsesNativeActionLabel() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                appUpdateStatusProvider = {
                    DataResult.Content(
                        SharedSettingsUpdateCheckSnapshot(
                            supported = true,
                            statusLabel = "Install",
                            actionLabel = "Install",
                            updateAvailable = true,
                            currentVersionCode = 1_000_000L,
                            latestVersionCode = 1_000_100L
                        )
                    )
                },
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadNotifications()

        val notification = (result as DataResult.Content).data.single()
        assertEquals("Install", notification.actionLabel)
        assertTrue(notification.actionLabelDesc != null)
    }

    @Test
    fun appUpdateNotificationUsesTransactionStateInsteadOfLocalizedHostLabels() {
        val ready =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall,
                statusLabel = "已准备好",
                actionLabel = "立即安装",
                updateAvailable = true,
                currentVersionCode = 1_000_000L,
                latestVersionCode = 1_000_100L
            ).toSharedHomeAppUpdateNotification()
        val downloading =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                transactionState = SharedSettingsUpdateTransactionState.Patching,
                statusLabel = "准备中",
                updateAvailable = true,
                currentVersionCode = 1_000_000L,
                latestVersionCode = 1_000_100L
            ).toSharedHomeAppUpdateNotification()

        assertEquals("Install", ready?.actionLabel)
        assertTrue(ready?.actionLabelDesc != null)
        assertEquals("Downloading", downloading?.actionLabel)
        assertTrue(downloading?.actionLabelDesc != null)
    }

    @Test
    fun loadNotificationsSkipsAppUpdateWhenLatestCodeDoesNotBeatCurrentCode() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                appUpdateStatusProvider = {
                    DataResult.Content(
                        SharedSettingsUpdateCheckSnapshot(
                            supported = true,
                            updateAvailable = true,
                            currentVersionLabel = "1.0.1",
                            currentVersionCode = 1_000_100L,
                            latestVersionLabel = "1.0.1",
                            latestVersionCode = 1_000_100L
                        )
                    )
                },
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadNotifications()

        assertEquals(emptyList(), (result as DataResult.Content).data)
    }

    @Test
    fun appUpdateNotificationIsLocalDismissable() {
        assertTrue(APP_UPDATE_HOME_NOTIFICATION_ID.isLocalHomeNotificationId())
    }

    @Test
    fun machineNotificationsAreLocalAndClassifiedForComputersDestination() {
        assertTrue("machine-offline:machine-1".isLocalHomeNotificationId())
        assertTrue("machine-outdated:0.0.68:machine-1,machine-2".isLocalHomeNotificationId())
        assertTrue("machine-offline:machine-1".isMachineHomeNotificationId())
        assertTrue("machine-outdated:0.0.68:machine-1,machine-2".isMachineHomeNotificationId())
        assertFalse(APP_UPDATE_HOME_NOTIFICATION_ID.isMachineHomeNotificationId())
        assertFalse("announcement-1".isMachineHomeNotificationId())
    }

    @Test
    fun loadHomeDoesNotBlockOnAgentDirectMessageStatusEnrichment() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val agentDm =
            ConversationSummary(
                threadId = ThreadId("dm-agent-1"),
                channelId = ChannelId("dm-agent-1"),
                kind = ConversationKind.DirectMessage,
                name = "Agent One",
                directMessagePeerType = "agent",
                presenceMemberId = MemberId("agent-1")
            )
        val humanDm =
            ConversationSummary(
                threadId = ThreadId("dm-human-1"),
                channelId = ChannelId("dm-human-1"),
                kind = ConversationKind.DirectMessage,
                name = "Human One",
                directMessagePeerType = "user",
                presenceMemberId = MemberId("human-1")
            )
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Agent One",
                            handle = "agent-one",
                            kind = MemberKind.Agent,
                            presence = Presence.Active,
                            agentStatus = AgentStatus.Working
                        ),
                        MemberProfile(
                            id = MemberId("human-1"),
                            displayName = "Human One",
                            handle = "human-one",
                            kind = MemberKind.Human,
                            presence = Presence.Active
                        )
                    )
            )
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                memberRepository = memberRepository,
                parseHome = { _, session ->
                    DataResult.Content(
                        sampleHome(ServerId(session.currentServerId!!)).copy(
                            directMessages = listOf(agentDm.toHomeDirectMessageRow(), humanDm.toHomeDirectMessageRow())
                        )
                    )
                }
            )

        val result = source.loadHome()

        val dms = (result as DataResult.Content).data.directMessages
        assertEquals(MemberId("agent-1"), dms[0].presenceMemberId)
        assertEquals(MemberId("human-1"), dms[1].presenceMemberId)
        assertEquals(0, memberRepository.getMembersCalls)

        val patch = source.refreshAgentDirectMessageStatuses(result.data)

        assertFalse(patch.changed)
        assertEquals(0, memberRepository.getMembersCalls)
        assertEquals(emptyList(), patch.directMessages)
        // The local home snapshot cache is gone (fb845a19): loadHome does not
        // write it and writeHomeSnapshot is a no-op that reports false.
        assertNull(localStore.readHome(ServerId("server-1")))

        assertFalse(source.writeHomeSnapshot(patch.snapshot, source = "test_agent_dm_status_refresh"))

        assertNull(localStore.readHome(ServerId("server-1")))
    }

    @Test
    fun backgroundLoadFetchesNetworkWithoutCachedHomeShortCircuit() = runBlocking {
        // The cached-home short circuit for background loads was removed together
        // with the home snapshot blob cache (fb845a19 "fix(home): remove home
        // snapshot blob cache"): background loads now always fetch the network.
        val serverId = ServerId("server-1")
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(serverId))
        val api = FakeHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = api,
                parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
            )

        val result = source.loadHome(priority = DataRequestPriority.Background)

        assertEquals(4, api.calls)
        assertEquals("general", (result as DataResult.Content).data.channels.single().name)
    }

    @Test
    fun agentStatusProjectionReusesLoadedMemberSnapshotWithoutNetworkRefresh() = runBlocking {
        val members =
            listOf(
                MemberProfile(
                    id = MemberId("agent-1"),
                    displayName = "Agent One",
                    handle = "agent-one",
                    kind = MemberKind.Agent,
                    presence = Presence.Active,
                    agentStatus = AgentStatus.Working
                )
            )
        val memberRepository = FakeMemberRepository(members)
        val memberStore = SharedServerMembersStore(memberRepository)
        memberStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = members
            )
        )
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                serverMembersStore = memberStore,
                parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
            )
        val snapshot = sampleHome(ServerId("server-1"))

        val patch = source.refreshAgentDirectMessageStatuses(snapshot)

        assertFalse(patch.changed)
        assertEquals(0, memberRepository.getMembersCalls)
    }

    @Test
    fun cachedHomeReadReturnsEmptyWithoutNetwork() = runBlocking {
        // readCachedHome always reports Empty(NoChannels) since the home snapshot
        // blob cache was removed (fb845a19); it must still never hit the network.
        val serverId = ServerId("server-1")
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(serverId))
        val api = FakeHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = api,
                parseHome = { _, _ -> error("cached read should not parse network") }
            )

        val result = source.readCachedHome(serverId)

        assertEquals(0, api.calls)
        assertTrue(result is DataResult.Empty)
        assertEquals(EmptyReason.NoChannels, (result as DataResult.Empty).reason)
    }

    @Test
    fun visibleLoadMapsThrownNetworkFailureToError() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = ThrowingHomeApi(),
                parseHome = { _, _ -> error("network failure should not parse") },
                diagnosticsLogger = logs::add
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_network", result.error.code)
        assertContains(logs, "home_endpoint category=channels result=exception errorType=IllegalStateException")
        assertContains(logs, "home_bootstrap_exception errorType=IllegalStateException mapped=home_network")
    }

    @Test
    fun loadHomeLogsEndpointCategoryAndContractMismatch() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(channelsBody = """{"channels":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") },
                diagnosticsLogger = logs::add
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_channels_contract_mismatch", result.error.code)
        assertContains(
            logs,
            "home_endpoint category=channels code=200 result=error code=home_channels_contract_mismatch"
        )
        assertTrue(logs.any { it.contains("home_parse_start source=home_channels") && it.contains("dispatcher=JsonParse") })
        assertTrue(
            logs.any {
                it.contains("home_parse_result source=home_channels result=error code=home_channels_contract_mismatch") &&
                    it.contains("dispatcher=JsonParse")
            }
        )
        assertContains(
            logs,
            "home_bootstrap_result result=error code=home_channels_contract_mismatch stage=payload"
        )
    }

    @Test
    fun loadHomeRejectsGuessedHomeWrappersBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(channelsBody = """{"channels":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_channels_contract_mismatch", result.error.code)
    }

    @Test
    fun loadHomeRejectsGuessedDirectMessageWrappersBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(directMessagesBody = """{"directMessages":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_direct_messages_contract_mismatch", result.error.code)
    }

    // task #831: a contract-mismatched unread section is still rejected (never
    // mis-accepted as data — it does not reach the payload), but per the Home
    // decoupling it must no longer abort the canonical channels/DMs. Previously
    // these asserted the whole Home returned Error on a guessed unread wrapper.
    @Test
    fun guessedUnreadCountWrapperIsRejectedWithoutBlockingCanonicalHome() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(unreadCountsBody = """[{"channelId":"channel-1","unreadCount":1}]"""),
                parseHome = { payload, session ->
                    // Guessed array shape rejected → not mis-accepted as unread data.
                    assertEquals(emptyMap(), payload.unreadCounts)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(
            result is DataResult.Content,
            "canonical Home must load even when the unread-counts wire shape is rejected"
        )
        assertEquals(1, result.data.channels.size)
    }

    @Test
    fun guessedServerUnreadSummaryWrapperIsRejectedWithoutBlockingCanonicalHome() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = FakeHomeApi(serverUnreadSummaryBody = """{"unreadCount":24}"""),
                parseHome = { payload, session ->
                    // Guessed object shape rejected → not mis-accepted as summary data.
                    assertEquals(emptyList(), payload.serverUnreadSummary)
                    assertFalse(payload.serverUnreadSummaryAvailable)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(
            result is DataResult.Content,
            "canonical Home must load even when the server-unread-summary wire shape is rejected"
        )
        assertEquals(1, result.data.channels.size)
    }

    @Test
    fun loadHomeUsesRequestedServerForEveryApiCallAndSessionSelection() = runBlocking {
        val sessionStore =
            InMemoryRaftAuthSessionStore(
                sampleSession().copy(
                    selectedServerId = "server-1",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-1", name = "Server One"),
                            StoredAuthServer(id = "server-2", name = "Server Two", slug = "two")
                        )
                )
            )
        val api = RecordingHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = sessionStore,
                api = api,
                parseHome = { payload, _ ->
                    DataResult.Content(sampleHome(payload.serverId))
                }
            )

        val result = source.loadHome(ServerId("server-2"))

        assertEquals(ServerId("server-2"), (result as DataResult.Content).data.server.id)
        assertEquals(listOf("channels:server-2", "dms:server-2", "unread:server-2", "summary:server-2"), api.calls)
    }

    @Test
    fun serverSwitchStateAcceptsOnlyLatestRequestedServer() {
        val initial = HomeServerSwitchState().select("server-a")
        val firstGeneration = initial.requestGeneration
        val latest = initial.select("server-b")
        val latestGeneration = latest.requestGeneration

        assertTrue(HomeServerSwitchState().isCurrent(0, ServerId("server-a")))
        assertTrue(!latest.isCurrent(firstGeneration, ServerId("server-a")))
        assertTrue(!latest.isCurrent(firstGeneration, ServerId("server-b")))
        assertTrue(latest.isCurrent(latestGeneration, ServerId("server-b")))
        assertEquals("server-b", latest.complete(latestGeneration, ServerId("server-b")).selectedServerId)
        assertEquals(null, latest.complete(latestGeneration, ServerId("server-b")).pendingServerId)
    }

    // task #831: the canonical Home surface (channels + DMs) must not be blocked
    // or failed by the auxiliary unread endpoints (channelUnreadCounts /
    // serverUnreadSummary). Historically homePayload awaited all four sections
    // and returned the aggregate terminal state, so a hung or erroring unread
    // endpoint aborted/hung the whole Home even after channels/DMs had loaded.
    @Test
    fun erroringServerUnreadSummaryMustNotBlockCanonicalHome() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = UnreadAuxiliaryHomeApi(errorSummary = true),
                parseHome = { payload, session ->
                    assertFalse(payload.serverUnreadSummaryAvailable)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = withTimeout(5_000L) { source.loadHome() }

        assertTrue(
            result is DataResult.Content,
            "canonical Home must still load when the server-unread-summary endpoint errors"
        )
        assertEquals(1, result.data.channels.size)
    }

    @Test
    fun erroringChannelUnreadCountsMustNotBlockCanonicalHome() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = UnreadAuxiliaryHomeApi(errorCounts = true),
                parseHome = { payload, session ->
                    assertTrue(payload.serverUnreadSummaryAvailable)
                    assertFalse(payload.unreadEnrichmentAvailable)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = withTimeout(5_000L) { source.loadHome() }

        assertTrue(
            result is DataResult.Content,
            "canonical Home must still load when the channel-unread-counts endpoint errors"
        )
        assertEquals(1, result.data.channels.size)
    }

    @Test
    fun hungUnreadEndpointMustNotBlockCanonicalHome() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = UnreadAuxiliaryHomeApi(hangSummary = true),
                parseHome = { payload, session ->
                    // task #831: a hung/unavailable unread section flags the
                    // payload so the Store keeps prior badges instead of zeroing.
                    assertFalse(payload.unreadEnrichmentAvailable)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        // Before the decoupling homePayload hard-awaited the unread deferred, so a
        // never-completing unread endpoint suspended the whole load and this
        // bounded wait timed out.
        val result = withTimeout(5_000L) { source.loadHome() }

        assertTrue(
            result is DataResult.Content,
            "canonical Home must still load when an unread endpoint hangs indefinitely"
        )
        assertEquals(1, result.data.channels.size)
    }

    @Test
    fun cancellationReachingAnUnreadSectionIsPreservedNotSwallowedAsPartialLoad() = runBlocking {
        // task #831 follow-up: awaitBestEffortHomeSection swallows an *erroring* or
        // *timed-out* unread section to null (badges kept, not zeroed) — but a real
        // CancellationException must be re-thrown, never funnelled through the
        // best-effort catch into a "partial load succeeded" result. If the caller
        // walked away (its coroutine cancelled), the in-flight unread await delivers
        // a CancellationException; reporting that as Content would let the Store
        // render a half-built snapshot nobody is waiting for. The dedicated
        // CancellationException catch (ahead of the broad Throwable catch) is what
        // this guards — remove it and the cancellation is swallowed to null and the
        // load completes as Content instead of cancelling.
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                api = CancellingUnreadHomeApi(),
                parseHome = { _, session ->
                    // Reaching here means the cancellation was swallowed into a
                    // partial payload; a preserved cancellation aborts before parse.
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val load = async { source.loadHome() }
        load.join()

        assertTrue(
            load.isCancelled,
            "a CancellationException from an unread section must propagate (real cancellation preserved), " +
                "not be swallowed by the best-effort catch into a partial Content load"
        )
    }

    private class UnreadAuxiliaryHomeApi(
        private val errorSummary: Boolean = false,
        private val errorCounts: Boolean = false,
        private val hangSummary: Boolean = false,
        private val hangCounts: Boolean = false,
    ) : RaftHomeApi {
        private val neverCompletes = CompletableDeferred<HomeNetworkResponse>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok("[]")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok("[]")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse {
            if (hangCounts) return neverCompletes.await()
            if (errorCounts) throw RuntimeException("simulated channel_unread_counts failure")
            return ok("{}")
        }

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse {
            if (hangSummary) return neverCompletes.await()
            if (errorSummary) throw RuntimeException("simulated server_unread_summary failure")
            return ok("[]")
        }

        private fun ok(body: String): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class CancellingUnreadHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok("[]")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok("[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = ok("{}")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse {
            // Simulates the in-flight unread request being cancelled — exactly what
            // a real caller cancellation delivers to the awaited unread section.
            throw CancellationException("simulated cancellation reaching the unread section")
        }

        private fun ok(body: String): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class FakeHomeApi(
        private val channelsBody: String = "[]",
        private val directMessagesBody: String = "[]",
        private val unreadCountsBody: String = "{}",
        private val serverUnreadSummaryBody: String = "[]"
    ) : RaftHomeApi {
        var calls: Int = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok(channelsBody).also { calls++ }
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok(directMessagesBody).also { calls++ }
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = ok(unreadCountsBody).also { calls++ }
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = ok(serverUnreadSummaryBody).also { calls++ }

        private fun ok(body: String): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class ConcurrentHomeApi : RaftHomeApi {
        val calls = mutableListOf<String>()
        private val channelsStarted = CompletableDeferred<Unit>()
        private val directMessagesStarted = CompletableDeferred<Unit>()
        private val unreadCountsStarted = CompletableDeferred<Unit>()
        private val serverUnreadSummaryStarted = CompletableDeferred<Unit>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse {
            calls += "channels"
            channelsStarted.complete(Unit)
            directMessagesStarted.await()
            unreadCountsStarted.await()
            serverUnreadSummaryStarted.await()
            return ok("[]")
        }

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse {
            calls += "direct_messages"
            directMessagesStarted.complete(Unit)
            channelsStarted.await()
            return ok("[]")
        }

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse {
            calls += "channel_unread_counts"
            unreadCountsStarted.complete(Unit)
            channelsStarted.await()
            return ok("{}")
        }

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse {
            calls += "server_unread_summary"
            serverUnreadSummaryStarted.complete(Unit)
            channelsStarted.await()
            return ok("[]")
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class ConcurrentAnnouncementsApi : RaftAnnouncementsRemindersApi {
        val announcementsStarted = CompletableDeferred<Unit>()
        lateinit var machinesStarted: CompletableDeferred<Unit>

        override suspend fun activeAnnouncements(): HomeNetworkResponse {
            announcementsStarted.complete(Unit)
            machinesStarted.await()
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "announcements": [
                        {
                          "id": "announcement-1",
                          "title": "Upgrade available",
                          "pages": [
                            { "body": "Install the newest desktop daemon." }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
            )
        }

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse =
            error("unused")

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            error("unused")
    }

    private fun notificationPreferences(
        offlineComputerAlertsEnabled: Boolean
    ): NotificationPreferenceRepository =
        object : NotificationPreferenceRepository {
            override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
                NotificationPreferenceSnapshot(
                    enabled = true,
                    offlineComputerAlertsEnabled = offlineComputerAlertsEnabled,
                )

            override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                error("unused")

            override suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                error("unused")

            override suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                error("unused")
        }

    private class ConcurrentComputersApi(
        private val announcementsStarted: CompletableDeferred<Unit>,
        private val bodyText: String =
            """
            {
              "machines": [
                {
                  "id": "machine-1",
                  "name": "OranixSlockTest",
                  "status": "offline",
                  "statusVersion": 1
                }
              ]
            }
            """.trimIndent(),
    ) : RaftComputersApi {
        val machinesStarted = CompletableDeferred<Unit>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            machinesStarted.complete(Unit)
            announcementsStarted.await()
            return HomeNetworkResponse(
                code = 200,
                bodyText = bodyText,
            )
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse =
            error("unused")

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
            error("unused")

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("unused")

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("unused")

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("unused")

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
            error("unused")

        override suspend fun deleteMachineWorkspace(
            serverId: ServerId,
            machineId: String,
            directoryName: String
        ): HomeNetworkResponse =
            error("unused")
    }

    private class RejectingComputersApi : RaftComputersApi {
        var machineCalls: Int = 0

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            machineCalls += 1
            error("Members without manageMachines must not load machine notifications")
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")

        override suspend fun deleteMachineWorkspace(
            serverId: ServerId,
            machineId: String,
            directoryName: String
        ): HomeNetworkResponse = error("unused")
    }

    private class ThrowingHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            throw IllegalStateException("offline")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")
    }

    private class RecordingHomeApi : RaftHomeApi {
        val calls = mutableListOf<String>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("channels", serverId)

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("dms", serverId)

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("unread", serverId)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("summary", serverId)

        private fun ok(name: String, serverId: ServerId): HomeNetworkResponse {
            calls += "$name:${serverId.value}"
            val body =
                when (name) {
                    "channels", "dms", "summary" -> "[]"
                    else -> "{}"
                }
            return HomeNetworkResponse(code = 200, bodyText = body)
        }
    }

    private class FakeMemberRepository(
        private val members: List<MemberProfile>
    ) : MemberRepository {
        var getMembersCalls: Int = 0
            private set

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            getMembersCalls += 1
            return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Slock"), members))
        }

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId, serverId: ServerId?): DataResult<MemberProfile> =
            error("unused")

        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> =
            error("unused")

        override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> =
            error("unused")

        override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> =
            error("unused")

        override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> =
            error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> =
            error("unused")

        override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> =
            error("unused")

        override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun assignAgentMachine(
            priority: DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> =
            error("unused")

        override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> =
            error("unused")

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> =
            error("unused")
    }

    private fun sampleSession(role: String? = "owner"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Community", role = role))
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock Community"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human,
                    presence = Presence.Active
                ),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
