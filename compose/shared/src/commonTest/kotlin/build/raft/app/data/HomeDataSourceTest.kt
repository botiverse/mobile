package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
                        HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

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
    fun homePayloadTagsLifecycleRetiredCodeZeroWithoutMisclassifyingRealNetworkErrors() = runBlocking {
        fun apiWithDmResponse(dmResponse: HomeNetworkResponse) =
            object : RaftHomeApi {
                override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")

                override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = dmResponse

                override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

                override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")
            }

        val retiredResult =
            RaftHomeApiClient(
                apiWithDmResponse(
                    HomeNetworkResponse(
                        code = 0,
                        bodyText = null,
                        errorBodyText = RAFT_NETWORK_LIFECYCLE_RETIRED_CODE,
                    )
                )
            ).homePayload(ServerId("server-1"), DataRequestPriority.Visible)
        val retiredError = assertIs<DataResult.Error>(retiredResult).error
        assertEquals("home_http_0", retiredError.code)
        assertEquals(RAFT_NETWORK_LIFECYCLE_RETIRED_CODE, retiredError.serverCode)
        assertTrue(retiredError.isLifecycleRetired())

        listOf(
            HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = null),
            HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = """{"error":"network_timeout"}"""),
            HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = "network_request_mutation_outcome_unknown"),
            HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = RAFT_NETWORK_LIFECYCLE_RETIRED_CODE),
        ).forEach { response ->
            val result = RaftHomeApiClient(apiWithDmResponse(response)).homePayload(ServerId("server-1"), DataRequestPriority.Visible)
            val error = assertIs<DataResult.Error>(result).error
            assertFalse(error.isLifecycleRetired(), "must not mark $response as lifecycle_retired")
        }
    }

    @Test
    fun offlineComputersNeverSurfaceHomeAlertsWithoutSuppressingOutdatedAlerts() = runBlocking {
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

        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
                api = FakeHomeApi(),
                computersApi =
                    ConcurrentComputersApi(
                        announcementsStarted = CompletableDeferred(Unit),
                        bodyText = machinesBody,
                    ),
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                },
            )
        val notifications = (source.loadNotifications() as DataResult.Content).data

        assertEquals(
            listOf("machine-outdated:0.63.7:machine-outdated"),
            notifications.map { it.id },
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
        assertEquals("Update available for Raw daemon", notification.title)
        assertEquals("raw-daemon", notification.targetId)
    }

    @Test
    fun homeMachineListVersionGateBecomesAComputerDeadlineNotice() = runBlocking {
        val computersApi =
            ConcurrentComputersApi(
                announcementsStarted = CompletableDeferred(Unit),
                bodyText =
                    """
                    {
                      "machines": [
                        {
                          "id": "old-computer",
                          "name": "Old computer",
                          "status": "online",
                          "statusVersion": 1,
                          "isComputer": true,
                          "computerVersion": "1.0.40",
                          "versionGate": {"reason": "computer_version_unsupported", "minVersion": "1.0.53", "enforcedAt": "2026-11-10T00:00:00.000Z", "enforced": false}
                        }
                      ]
                    }
                    """.trimIndent(),
            )
        val source =
            SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
                api = FakeHomeApi(),
                computersApi = computersApi,
                parseHome = { _, session ->
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                },
            )

        val notifications = (source.loadNotifications() as DataResult.Content).data

        assertEquals(
            listOf("computer-version-gate:reinstall:2026-11-10T00:00:00.000Z:old-computer"),
            notifications.map { it.id },
        )
        assertEquals("old-computer", notifications.single().targetId)
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

        assertEquals("Update ready to install", ready?.title)
        assertEquals("Install", ready?.actionLabel)
        assertTrue(ready?.actionLabelDesc != null)
        assertEquals("App update available", downloading?.title)
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

    /** Tenny 10-27: a machine change drops the held list; the next Home reload reads it again. */
    @Test
    fun syncOnDroppedMachineListIsReadAgainByTheNextHomeReload() = runBlocking {
        val computersApi = ConcurrentComputersApi(CompletableDeferred(Unit))
        val source = syncOnMachinesSource(computersApi)
        val beforeDrop = source.loadNotificationsAfterHomeLoad() as DataResult.Content
        source.loadNotificationsAfterHomeLoad()
        assertEquals(1, computersApi.machineCalls)
        assertEquals(listOf("machine-outdated:0.63.7:machine-1"), beforeDrop.data.map { it.id })

        source.dropHeldMachines()
        computersApi.bodyText = ONLINE_MACHINE_BODY
        val afterChange = source.loadNotificationsAfterHomeLoad() as DataResult.Content

        assertEquals(2, computersApi.machineCalls)
        assertEquals(emptyList(), afterChange.data.map { it.id }, "the outdated alert left with the fresh list")
    }

    /**
     * A machine read in flight when the held list is dropped may have been answered before the
     * change: landing after the drop it is not held, so the next sync-on Home reload reads again.
     */
    @Test
    fun syncOnMachineReadLandingAfterADropIsNotHeld() = runTest {
        val machinesAnswer = CompletableDeferred<Unit>()
        val computersApi = ConcurrentComputersApi(machinesAnswer)
        val source = syncOnMachinesSource(computersApi)
        val inFlight = async { source.loadNotificationsAfterHomeLoad() }
        computersApi.machinesStarted.await()

        source.dropHeldMachines()
        machinesAnswer.complete(Unit)
        assertEquals(listOf("machine-outdated:0.63.7:machine-1"), (inFlight.await() as DataResult.Content).data.map { it.id })
        computersApi.bodyText = ONLINE_MACHINE_BODY
        val next = source.loadNotificationsAfterHomeLoad() as DataResult.Content

        assertEquals(2, computersApi.machineCalls, "the next reload read the list again")
        assertEquals(emptyList(), next.data.map { it.id }, "not the outdated list read before the change")
    }

    private fun syncOnMachinesSource(computersApi: ConcurrentComputersApi) =
        SharedHomeDataSource(
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "admin")),
            api = FakeHomeApi(),
            computersApi = computersApi,
            serverMembersStore = SharedServerMembersStore(FakeMemberRepository(emptyList()), incrementalSyncCoversRoster = { true }),
            parseHome = { _, session -> DataResult.Content(sampleHome(ServerId(session.currentServerId!!))) },
        )

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
            return ok(EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)
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
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            ok(EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

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
        private val unreadCountsBody: String = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY,
        private val serverUnreadSummaryBody: String = "[]"
    ) : RaftHomeApi {
        var calls: Int = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok(channelsBody).also { calls++ }
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok(directMessagesBody).also { calls++ }
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = ok(unreadCountsBody).also { calls++ }
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = ok(serverUnreadSummaryBody).also { calls++ }

        private fun ok(body: String): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = body)
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

    private class ConcurrentComputersApi(
        private val announcementsStarted: CompletableDeferred<Unit>,
        var bodyText: String =
            """
            {
              "latestDaemonVersion": "0.63.7",
              "machines": [
                {
                  "id": "machine-1",
                  "name": "OranixSlockTest",
                  "status": "online",
                  "statusVersion": 1,
                  "daemonVersion": "0.62.0"
                }
              ]
            }
            """.trimIndent(),
    ) : RaftComputersApi {
        val machinesStarted = CompletableDeferred<Unit>()
        var machineCalls: Int = 0

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            machineCalls += 1
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
                    else -> EMPTY_CHANNEL_UNREAD_SUMMARY_BODY
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

private val ONLINE_MACHINE_BODY =
    """
    {
      "machines": [
        {
          "id": "machine-1",
          "name": "OranixSlockTest",
          "status": "online",
          "statusVersion": 2
        }
      ]
    }
    """.trimIndent()
