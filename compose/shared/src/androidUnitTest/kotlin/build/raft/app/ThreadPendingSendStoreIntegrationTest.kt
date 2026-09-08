package build.raft.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.SlockSqlDatabase
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadPendingSendJournalFailureInjector
import build.raft.app.data.WireMessage
import build.raft.app.shared.data.local.SlockDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end JVM coverage for the store/journal boundary.
 *
 * The API spy is intentionally shared by the pre-rebuild and post-rebuild stores.  A zero call
 * count after the second store starts proves that restoring a durable row is only a projection;
 * the explicit retry below is the positive control proving the same send boundary is live.
 */
class ThreadPendingSendStoreIntegrationTest {
    @Test
    fun processRebuildRestoresPendingWithoutNetworkAndExplicitRetrySendsOnceWithSameRandomId() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val identity = identity()
            val routePayload = basePayload()
            val pending = pendingMessage("local-send-rebuild", "random-rebuild", createdAt = 10L)

            val first = open(path, api, identity)
            first.store.accept(SharedThreadIntent.Start(routePayload))
            val firstProvenance = requireNotNull(first.dataSource.currentReplyCacheProvenance())
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                first.store.admitLocalSend(
                    message = pending,
                    expectedProvenance = firstProvenance,
                    expectedRoute = route
                )
            )
            assertEquals(listOf(pending.id), first.journal.restore(firstProvenance, route).map { it.id })
            first.scope.cancel()
            first.driver.close()

            // Mark the same durable row failed before the new process starts.  This models a
            // definite terminal failure that is eligible for a user-visible retry.
            val second = open(path, api, identity)
            val secondProvenance = identity.toProvenance()
            assertTrue(
                second.journal.markFailed(
                    provenance = secondProvenance,
                    route = route,
                    localId = pending.id,
                    errorCode = "thread_send_network",
                    errorMessage = "offline"
                )
            )
            second.store.accept(SharedThreadIntent.Start(routePayload))
            val restored = second.store.state.value.payload.messages.firstOrNull { it.id == pending.id }
            assertNotNull(restored, "the second store must load the durable pending projection")
            assertEquals(ThreadMessageDeliveryFailed, restored.deliveryState)
            assertEquals(0, api.sendMessageCalls, "process recreation must never auto-send")

            // Positive control: only an explicit retry crosses the network boundary.  Reuse the
            // restored row's context, so the idempotency key is demonstrably unchanged.
            val retryProvenance = requireNotNull(second.dataSource.currentReplyCacheProvenance())
            val retryRoute = second.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                second.store.admitLocalSend(
                    message = restored.copy(
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendErrorCode = null,
                        pendingSendErrorMessage = null
                    ),
                    expectedProvenance = retryProvenance,
                    expectedRoute = retryRoute
                )
            )
            val randomId = restored.pendingSendContext?.randomId
            assertNotNull(randomId)
            api.sendResponseBody = sentMessageResponse(randomId)
            val retryResult =
                second.dataSource.sendMessage(
                    channelId = restored.pendingSendContext!!.channelId,
                    body = restored.pendingSendContext.body,
                    asTask = restored.pendingSendContext.asTask,
                    attachmentIds = restored.pendingSendContext.attachmentIds,
                    randomId = randomId
                )
            assertTrue(
                retryResult is ThreadApiResult.Success<*>,
                "the explicit retry request should receive the valid canonical send response"
            )
            assertEquals(1, api.sendMessageCalls, "explicit retry must cross the boundary once")
            assertEquals(randomId, api.lastRandomId)
            second.scope.cancel()
            second.driver.close()
        }
    }

    @Test
    fun journalAdmissionFailureLeavesStoreAndNetworkUntouched() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val identity = identity()
            val opened = open(
                path = path,
                api = api,
                identity = identity,
                failureInjector = ThreadPendingSendJournalFailureInjector(rejectAdmission = { true }),
            )
            opened.store.accept(SharedThreadIntent.Start(basePayload()))
            val provenance = requireNotNull(opened.dataSource.currentReplyCacheProvenance())
            val route = opened.store.state.value.payload.resolveThreadRouteIdentity()
            assertFalse(
                opened.store.admitLocalSend(
                    message = pendingMessage("local-send-write-failure", "random-write-failure"),
                    expectedProvenance = provenance,
                    expectedRoute = route
                )
            )
            assertTrue(opened.store.state.value.payload.messages.none { it.id == "local-send-write-failure" })
            assertEquals(0, api.sendMessageCalls)
            opened.scope.cancel()
            opened.driver.close()
        }
    }

    @Test
    fun terminalPathsClearOrRetainTheDurableProjectionPrecisely() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val identity = identity()
            var rejectDelete = false
            val opened = open(
                path = path,
                api = api,
                identity = identity,
                failureInjector = ThreadPendingSendJournalFailureInjector(rejectDelete = { rejectDelete }),
            )
            opened.store.accept(SharedThreadIntent.Start(basePayload()))
            val provenance = requireNotNull(opened.dataSource.currentReplyCacheProvenance())
            val route = opened.store.state.value.payload.resolveThreadRouteIdentity()

            val success = pendingMessage("local-send-success", "random-success")
            assertTrue(opened.store.admitLocalSend(success, provenance, route))
            opened.store.accept(
                SharedThreadIntent.ReplaceLocalSendWithSentPage(
                    messageId = success.id,
                    page = sentPage(success.pendingSendContext!!.randomId!!),
                    expectedProvenance = provenance,
                    expectedRoute = route
                )
            )
            assertTrue(opened.journal.restore(provenance, route).none { it.id == success.id })

            val failed = pendingMessage("local-send-failed", "random-failed")
            assertTrue(opened.store.admitLocalSend(failed, provenance, route))
            opened.store.accept(
                SharedThreadIntent.ApplyLocalSendFailure(
                    messageId = failed.id,
                    errorCode = "http_400",
                    errorMessage = "bad request",
                    expectedProvenance = provenance,
                    expectedRoute = route
                )
            )
            val failedRow = opened.journal.restore(provenance, route).single { it.id == failed.id }
            assertEquals(ThreadMessageDeliveryFailed, failedRow.deliveryState)
            assertEquals("http_400", failedRow.pendingSendErrorCode)

            val canceled = pendingMessage("local-send-canceled", "random-canceled")
            assertTrue(opened.store.admitLocalSend(canceled, provenance, route))
            opened.store.accept(SharedThreadIntent.CancelLocalSend(canceled.id))
            assertTrue(opened.journal.restore(provenance, route).none { it.id == canceled.id })

            val deleteFailure = pendingMessage("local-send-delete-failure", "random-delete-failure")
            assertTrue(opened.store.admitLocalSend(deleteFailure, provenance, route))
            rejectDelete = true
            opened.store.accept(SharedThreadIntent.CancelLocalSend(deleteFailure.id))
            assertTrue(
                opened.store.state.value.payload.messages.any { it.id == deleteFailure.id },
                "a failed durable delete must not be reported as a successful cancel"
            )
            opened.scope.cancel()
            opened.driver.close()
        }
    }

    @Test
    fun tokenRotationKeepsSuccessTerminalAndRehomesDefiniteFailure() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val initial = identity().copy(bindingGeneration = 3L)
            var currentIdentity = initial
            val opened =
                openWithIdentityProvider(
                    path = path,
                    api = api,
                    identityProvider = { currentIdentity },
                )
            opened.store.accept(SharedThreadIntent.Start(basePayload()))
            val oldProvenance = requireNotNull(opened.dataSource.currentReplyCacheProvenance())
            val route = opened.store.state.value.payload.resolveThreadRouteIdentity()

            val success = pendingMessage("local-send-rotated-success", "random-rotated-success")
            assertTrue(opened.store.admitLocalSend(success, oldProvenance, route))
            currentIdentity = initial.copy(authGeneration = initial.authGeneration + 1L)
            val successorProvenance =
                requireNotNull(opened.dataSource.captureReplyCacheProducerContext()).provenance
            assertTrue(
                opened.dataSource.isCurrentReplyCacheProvenanceForLocalSend(oldProvenance)
            )
            assertFalse(opened.dataSource.isCurrentReplyCacheProvenance(oldProvenance))
            assertTrue(
                opened.journal.clearForAuthRevision(
                    environmentId = oldProvenance.environmentId,
                    principalId = oldProvenance.principalId,
                    authGeneration = oldProvenance.authGeneration,
                )
            )
            opened.store.accept(
                SharedThreadIntent.ReplaceLocalSendWithSentPage(
                    messageId = success.id,
                    page = sentPage(success.pendingSendContext!!.randomId!!),
                    expectedProvenance = oldProvenance,
                    expectedRoute = route,
                )
            )
            assertTrue(opened.store.state.value.payload.messages.any { it.id == "server-random-rotated-success" })
            assertTrue(opened.store.state.value.payload.messages.none { it.id == success.id })
            assertTrue(opened.journal.restore(successorProvenance, route).isEmpty())

            val failure = pendingMessage("local-send-rotated-failure", "random-rotated-failure")
            assertTrue(opened.store.admitLocalSend(failure, successorProvenance, route))
            currentIdentity = initial.copy(authGeneration = initial.authGeneration + 2L)
            val terminalProvenance =
                requireNotNull(opened.dataSource.captureReplyCacheProducerContext()).provenance
            assertTrue(
                opened.journal.clearForAuthRevision(
                    environmentId = successorProvenance.environmentId,
                    principalId = successorProvenance.principalId,
                    authGeneration = successorProvenance.authGeneration,
                )
            )
            opened.store.accept(
                SharedThreadIntent.ApplyLocalSendFailure(
                    messageId = failure.id,
                    errorCode = "http_400",
                    errorMessage = "bad request",
                    expectedProvenance = successorProvenance,
                    expectedRoute = route,
                )
            )
            val failed = opened.journal.restore(terminalProvenance, route).single { it.id == failure.id }
            assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
            assertEquals("http_400", failed.pendingSendErrorCode)

            opened.scope.cancel()
            opened.driver.close()
        }
    }

    @Test
    fun emptyJournalIsSafeAndRowsRestoreInCreatedOrder() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val identity = identity()
            val opened = open(path, api, identity)
            val provenance = identity.toProvenance()
            val route = basePayload().resolveThreadRouteIdentity()
            assertTrue(opened.journal.restore(provenance, route).isEmpty())

            val later = pendingMessage("local-send-later", "random-later", createdAt = 20L)
            val earlier = pendingMessage("local-send-earlier", "random-earlier", createdAt = 10L)
            assertTrue(opened.journal.persistSending(provenance, route, later))
            assertTrue(opened.journal.persistSending(provenance, route, earlier))
            assertEquals(
                listOf(earlier.id, later.id),
                opened.journal.restore(provenance, route).map { it.id }
            )
            opened.scope.cancel()
            opened.driver.close()
        }
    }

    private data class Opened(
        val driver: JdbcSqliteDriver,
        val journal: ThreadPendingSendJournal,
        val dataSource: SharedThreadDataSource,
        val store: SharedThreadStore,
        val scope: CoroutineScope,
    )

    private fun open(
        path: java.nio.file.Path,
        api: FakeThreadApi,
        identity: ThreadReplyCacheIdentitySnapshot,
        failureInjector: ThreadPendingSendJournalFailureInjector? = null,
    ): Opened =
        openWithIdentityProvider(
            path = path,
            api = api,
            identityProvider = { identity },
            failureInjector = failureInjector,
        )

    private fun openWithIdentityProvider(
        path: java.nio.file.Path,
        api: FakeThreadApi,
        identityProvider: () -> ThreadReplyCacheIdentitySnapshot,
        failureInjector: ThreadPendingSendJournalFailureInjector? = null,
    ): Opened {
        val driver = JdbcSqliteDriver(url(path))
        val journal =
            ThreadPendingSendJournal(
                database = openDatabase(driver),
                nowMillis = { 100L },
                failureInjector = failureInjector,
            )
        val sessionStore = InMemorySlockAuthSessionStore(identityProvider().session)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                pendingSendJournal = journal,
                replyCacheIdentityAuthority = identityProvider,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Opened(
            driver = driver,
            journal = journal,
            dataSource = dataSource,
            store = SharedThreadStore(dataSource = dataSource, scope = scope),
            scope = scope,
        )
    }

    private fun openDatabase(driver: JdbcSqliteDriver): SlockSqlDatabase {
        val version =
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
                binders = null,
            ).value
        if (version == 0L) {
            SlockDatabase.Schema.create(driver).value
            driver.execute(
                identifier = null,
                sql = "PRAGMA user_version = ${SlockDatabase.Schema.version}",
                parameters = 0,
                binders = null,
            )
        }
        return SlockSqlDatabase(driver)
    }

    private fun identity(): ThreadReplyCacheIdentitySnapshot =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "u1", email = "artin@example.test", displayName = "Artin"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = "server-1",
                ),
            environmentId = "https://prod.example.test",
            principalId = "u1",
            serverId = "server-1",
            authGeneration = 7L,
            selectionGeneration = 11L,
        )

    private fun ThreadReplyCacheIdentitySnapshot.toProvenance(): ReplyCacheProvenance =
        ReplyCacheProvenance(
            environmentId = environmentId,
            principalId = principalId,
            serverId = serverId,
            generation = 1L,
            authGeneration = authGeneration,
            selectionGeneration = selectionGeneration,
        )

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "integration-thread",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun pendingMessage(localId: String, randomId: String, createdAt: Long? = null): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = localId,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = "pending body",
            threadChannelId = "channel-1",
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                KuiklyThreadPendingSendContext(
                    channelId = "channel-1",
                    body = "pending body",
                    streamChannelId = "channel-1",
                    randomId = randomId,
                ),
            createdAtEpochMillis = createdAt,
        )

    private fun sentPage(randomId: String): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = "server-$randomId",
                        channelId = "channel-1",
                        senderType = "user",
                        senderId = "u1",
                        senderName = "Artin",
                        messageType = "chat",
                        content = "pending body",
                        createdAt = "2026-08-25T00:00:00Z",
                        seq = 99L,
                        randomId = randomId,
                    )
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null,
        )

    /** Canonical POST /messages response shape from packages/server/src/routes/messages.ts. */
    private fun sentMessageResponse(randomId: String): String =
        """
        {
          "id": "server-$randomId",
          "seq": 99,
          "channelId": "channel-1",
          "senderType": "user",
          "senderId": "u1",
          "senderName": "Artin",
          "messageType": "chat",
          "content": "pending body",
          "randomId": "$randomId",
          "createdAt": "2026-08-25T00:00:00Z"
        }
        """.trimIndent()

    private suspend fun withDatabase(block: suspend (java.nio.file.Path) -> Unit) {
        val path = Files.createTempFile("slock-pending-store-", ".db")
        path.deleteIfExists()
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private fun url(path: java.nio.file.Path): String = "jdbc:sqlite:${path.toAbsolutePath()}"
}
