package build.raft.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.RaftJsonParseDispatcher
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadPendingSendJournalFailureInjector
import build.raft.app.data.WireMessage
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.shared.data.local.RaftDatabase
import kotlinx.coroutines.CoroutineDispatcher
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
            api.sendResponseBody = sentMessageV2Response(randomId)
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

    /**
     * Hands b19326ee / c2430c69 (Android 1.17.0): the local-send counter restarted in every
     * process and the auth generation rotated on each launch, so the journal held two rows with
     * the same local id under different generations. Both restored, and the duplicate id crashed
     * the message list. Re-entry must show one row: the newest.
     */
    @Test
    fun journalRowsSharingALocalIdAcrossAuthGenerationsRestoreAsOneRow() = runTest {
        withDatabase { path ->
            val api = FakeThreadApi()
            val identity = identity()
            val sharedLocalId = "local-send-integration-thread-1"
            val older = pendingMessage(sharedLocalId, "random-older-launch", createdAt = 10L)
            val newer = pendingMessage(sharedLocalId, "random-newer-launch", createdAt = 20L)

            val first = open(path, api, identity)
            first.store.accept(SharedThreadIntent.Start(basePayload()))
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            val previousLaunch = identity.copy(authGeneration = identity.authGeneration - 1L).toProvenance()
            assertTrue(first.journal.persistSending(previousLaunch, route, older))
            assertTrue(first.journal.persistSending(identity.toProvenance(), route, newer))
            assertEquals(
                listOf(sharedLocalId, sharedLocalId),
                first.journal.restore(identity.toProvenance(), route).map { it.id },
                "precondition: the journal holds two rows with one local id"
            )
            first.scope.cancel()
            first.driver.close()

            val second = open(path, api, identity)
            second.store.accept(SharedThreadIntent.Start(basePayload()))
            val messages = second.store.state.value.payload.messages
            val restored = messages.filter { it.id == sharedLocalId }
            assertEquals(1, restored.size, "one row per local id, got ${messages.map { it.id to it.randomId }}")
            assertEquals("random-newer-launch", restored.single().pendingSendContext?.randomId)
            val keys = messages.map { it.messageListItemKey(messages.threadDuplicateRandomIds()) }
            assertEquals(keys.size, keys.toSet().size, "LazyColumn keys must be unique: $keys")
            second.scope.cancel()
            second.driver.close()
        }
    }

    @Test
    fun reEntryWithFailedSendLoadsNewerServerMessagesAndKeepsFailedSendLast() = runTest {
        withDatabase { path ->
            val identity = identity()
            val routePayload = basePayload()
            val failedLocalId = "local-send-failed-reentry"

            val first = open(path, FakeThreadApi(bodyText = pageResponse(Triple("m10", 10L, "old"))), identity)
            first.store.accept(SharedThreadIntent.Start(routePayload))
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                first.store.admitLocalSend(
                    message = pendingMessage(failedLocalId, "random-reentry", createdAt = 10L),
                    expectedProvenance = requireNotNull(first.dataSource.currentReplyCacheProvenance()),
                    expectedRoute = route
                )
            )
            assertTrue(
                first.journal.markFailed(identity.toProvenance(), route, failedLocalId, "thread_send_network", "offline")
            )
            first.scope.cancel()
            first.driver.close()

            // Re-enter: the server moved on while the send was failed.
            val api =
                FakeThreadApi(
                    bodyText = pageResponse(Triple("m10", 10L, "old"), Triple("m11", 11L, "new one"), Triple("m30", 30L, "new two"))
                )
            val second = open(path, api, identity)
            second.store.accept(SharedThreadIntent.Start(routePayload))

            val payload = second.store.state.value.payload
            assertEquals(listOf("m10", "m11", "m30", failedLocalId), payload.messages.map { it.id })
            assertEquals(ThreadMessageDeliveryFailed, payload.messages.last().deliveryState)
            assertFalse(payload.canLoadMoreAfter, "a local-only failed send must not pin the timeline off the tail")
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

    /**
     * Feedback 03e9a2f6 (1.16.1, log replay): the in-page POST for "hi?" died (h2 stream reset)
     * while a server switch rehomed the journal, so its failure projection was blocked and the
     * durable row stayed "sending". Re-entering the DM, the drainer adopted it
     * (`thread_send_adopted_stale` -> failed projection), resent with the same randomId and got
     * 200 -- yet the page kept a failed copy beside the delivered message.
     */
    @Test
    fun pageEntryDrainOfAnOrphanedSendingRowEndsOnTheDeliveredMessageNotAFailure() = runTest {
        withDatabase { path ->
            val identity = identity()
            val routePayload = basePayload()
            val orphan = pendingMessage("local-send-orphan", "random-orphan", createdAt = 10L)

            val first = open(path, FakeThreadApi(), identity)
            first.store.accept(SharedThreadIntent.Start(routePayload))
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                first.store.admitLocalSend(
                    message = orphan,
                    expectedProvenance = requireNotNull(first.dataSource.currentReplyCacheProvenance()),
                    expectedRoute = route
                )
            )
            // The owning attempt dies without a durable terminal write.
            first.scope.cancel()
            first.driver.close()

            // The resend commits server-side, so the page-entry refresh's latest page carries it.
            val api =
                FakeThreadApi(bodyText = latestPageWithDelivered("random-orphan")).apply {
                    sendResponseBody = sentMessageV2Response("random-orphan")
                }
            val second = open(path, api, identity, threadParseDispatcher = Dispatchers.Unconfined)
            second.store.accept(SharedThreadIntent.Start(routePayload))

            assertEquals(1, api.sendMessageCalls, "the adoption resends exactly once")
            assertEquals("random-orphan", api.lastRandomId, "the resend reuses the idempotency key")
            val messages = second.store.state.value.payload.messages
            val timeline = messages.map { "${it.id}:${it.deliveryState}" }
            assertTrue(messages.any { it.id == "server-random-orphan" }, "the delivered message is shown: $timeline")
            assertTrue(
                messages.none { it.id == orphan.id },
                "a delivered send must not keep a failed local copy (and its Retry) beside it"
            )
            assertTrue(messages.none { it.deliveryState == ThreadMessageDeliveryFailed })
            assertTrue(second.journal.restore(identity.toProvenance(), route).isEmpty())
            second.scope.cancel()
            second.driver.close()
        }
    }

    /**
     * Task #73 (Tenny's rule 3): a journaled send keeps its structured `mentions` and every resend
     * carries that exact list — the person, agent and Slack participant picked in the composer (#77:
     * ids included), never re-resolved by name.
     */
    @Test
    fun aResentJournaledSendCarriesItsOwnMentionList() = runTest {
        withDatabase { path ->
            val identity = identity()
            val routePayload = basePayload()
            val mentions =
                listOf(
                    build.raft.app.data.ThreadSendMessageMention(
                        type = "external",
                        id = "7f8e2a3b-0c1d-4e5f-8a9b-0c1d2e3f4a5b",
                        name = "yezi",
                    ),
                    build.raft.app.data.ThreadSendMessageMention(
                        type = "user",
                        id = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9",
                        name = "ada",
                    ),
                    build.raft.app.data.ThreadSendMessageMention(
                        type = "agent",
                        id = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d",
                        name = "skyzh",
                    ),
                )
            val orphan = pendingMessage("local-send-mention", "random-mention", createdAt = 10L, mentions = mentions)

            val first = open(path, FakeThreadApi(), identity)
            first.store.accept(SharedThreadIntent.Start(routePayload))
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                first.store.admitLocalSend(
                    message = orphan,
                    expectedProvenance = requireNotNull(first.dataSource.currentReplyCacheProvenance()),
                    expectedRoute = route
                )
            )
            assertEquals(
                mentions,
                first.journal.restore(identity.toProvenance(), route).single().pendingSendContext?.mentions,
                "the journal keeps the mention list with the send",
            )
            first.scope.cancel()
            first.driver.close()

            val api =
                FakeThreadApi(bodyText = latestPageWithDelivered("random-mention")).apply {
                    sendResponseBody = sentMessageV2Response("random-mention")
                }
            val second = open(path, api, identity, threadParseDispatcher = Dispatchers.Unconfined)
            second.store.accept(SharedThreadIntent.Start(routePayload))

            assertEquals(1, api.sendMessageCalls, "the adoption resends exactly once")
            assertEquals("random-mention", api.lastRandomId)
            assertEquals(mentions, api.lastMentions, "the resend carries the journaled mention list as is")
            second.scope.cancel()
            second.driver.close()
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

    /**
     * proj-mobile#66 (follow-up of #3848): a send in flight on server A fails after the user
     * switched to server B. The switch is an auth revision advance for the same account, so
     * `rehomeAuthRevision` has already moved A's journal row to the successor generation. The
     * failure must still land on that row (A's own channel), show as failed with Retry when the
     * user returns to A, and survive a restart.
     *
     * Variant: A's page store has not re-read the identity since the switch (its provenance is
     * still A's), which is the path that logged `pending_journal_write_failed`.
     */
    @Test
    fun sendFailingAfterServerSwitchIsJournaledAsFailedForItsOwnServer() = runTest {
        sendFailingAfterServerSwitchScenario(pageSawServerB = false)
    }

    /** Same, when A's page store already observed server B before the failure arrived. */
    @Test
    fun sendFailingAfterServerSwitchIsJournaledEvenWhenThePageAlreadySeesServerB() = runTest {
        sendFailingAfterServerSwitchScenario(pageSawServerB = true)
    }

    private suspend fun sendFailingAfterServerSwitchScenario(pageSawServerB: Boolean) {
        withDatabase { path ->
            val serverA = identity().copy(bindingGeneration = 3L)
            var current = serverA
            val api = FakeThreadApi()
            val diagnostics = mutableListOf<String>()
            val first =
                openWithIdentityProvider(
                    path = path,
                    api = api,
                    identityProvider = { current },
                    diagnostics = diagnostics,
                )
            first.store.accept(SharedThreadIntent.Start(basePayload()))
            val provenanceA = requireNotNull(first.dataSource.currentReplyCacheProvenance())
            val routeA = first.store.state.value.payload.resolveThreadRouteIdentity()
            val pending = pendingMessage("local-send-switch", "random-switch", createdAt = 10L)
            assertTrue(first.store.admitLocalSend(pending, provenanceA, routeA))

            // Switch to server B: the account's auth revision advances (journal rows rehomed, as
            // RaftSharedStoreProvider's onBindingAuthRevisionRotated does) and the selection moves.
            assertEquals(
                1,
                first.journal.rehomeAuthRevision(
                    environmentId = serverA.environmentId,
                    principalId = serverA.principalId,
                    fromAuthGeneration = serverA.authGeneration,
                    toAuthGeneration = serverA.authGeneration + 1L,
                ),
            )
            current =
                serverA.copy(
                    session = serverA.session.copy(selectedServerId = "server-2"),
                    serverId = "server-2",
                    authGeneration = serverA.authGeneration + 1L,
                    selectionGeneration = serverA.selectionGeneration + 1L,
                )
            if (pageSawServerB) {
                assertEquals("server-2", first.dataSource.captureReplyCacheProducerContext()?.provenance?.serverId)
            }

            // A's request now fails.
            first.store.accept(
                SharedThreadIntent.ApplyLocalSendFailure(
                    messageId = pending.id,
                    errorCode = "http_400",
                    errorMessage = "bad request",
                    expectedProvenance = provenanceA,
                    expectedRoute = routeA,
                    randomId = "random-switch",
                )
            )
            assertTrue(
                diagnostics.none { it.contains("pending_journal_write_failed") },
                "the failure write must not be blocked: $diagnostics",
            )
            val journaled = first.journal.restore(provenanceA, routeA).single { it.id == pending.id }
            assertEquals(ThreadMessageDeliveryFailed, journaled.deliveryState, "A's row must record the failure")
            assertEquals("http_400", journaled.pendingSendErrorCode)

            // Back on server A (another switch, another revision): the page shows it failed, with Retry.
            assertEquals(
                1,
                first.journal.rehomeAuthRevision(
                    environmentId = serverA.environmentId,
                    principalId = serverA.principalId,
                    fromAuthGeneration = serverA.authGeneration + 1L,
                    toAuthGeneration = serverA.authGeneration + 2L,
                ),
            )
            val backOnA =
                serverA.copy(
                    authGeneration = serverA.authGeneration + 2L,
                    selectionGeneration = serverA.selectionGeneration + 2L,
                )
            current = backOnA
            first.scope.cancel()
            first.driver.close()

            val returned = open(path, api, backOnA)
            returned.store.accept(SharedThreadIntent.Start(basePayload()))
            val shown = returned.store.state.value.payload.messages.single { it.id == pending.id }
            assertEquals(ThreadMessageDeliveryFailed, shown.deliveryState)
            assertEquals("http_400", shown.pendingSendErrorCode)
            returned.scope.cancel()
            returned.driver.close()

            // And after an app restart.
            val restarted = open(path, api, backOnA)
            restarted.store.accept(SharedThreadIntent.Start(basePayload()))
            val afterRestart = restarted.store.state.value.payload.messages.single { it.id == pending.id }
            assertEquals(ThreadMessageDeliveryFailed, afterRestart.deliveryState)
            assertEquals("http_400", afterRestart.pendingSendErrorCode)
            assertEquals(0, api.sendMessageCalls, "a failed send waits for the user's Retry")
            restarted.scope.cancel()
            restarted.driver.close()
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
        threadParseDispatcher: CoroutineDispatcher? = null,
    ): Opened =
        openWithIdentityProvider(
            path = path,
            api = api,
            identityProvider = { identity },
            failureInjector = failureInjector,
            threadParseDispatcher = threadParseDispatcher,
        )

    /**
     * [threadParseDispatcher] = Unconfined keeps the store's own drainer (launched on the
     * Unconfined store scope) inline, so a drain started by Start has finished when it returns.
     */
    private fun openWithIdentityProvider(
        path: java.nio.file.Path,
        api: FakeThreadApi,
        identityProvider: () -> ThreadReplyCacheIdentitySnapshot,
        failureInjector: ThreadPendingSendJournalFailureInjector? = null,
        threadParseDispatcher: CoroutineDispatcher? = null,
        diagnostics: MutableList<String>? = null,
    ): Opened {
        val driver = JdbcSqliteDriver(url(path))
        val journal =
            ThreadPendingSendJournal(
                database = openDatabase(driver),
                nowMillis = { 100L },
                failureInjector = failureInjector,
            )
        val sessionStore = InMemoryRaftAuthSessionStore(identityProvider().session)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                pendingSendJournal = journal,
                replyCacheIdentityAuthority = identityProvider,
                threadParseDispatcher = threadParseDispatcher ?: RaftJsonParseDispatcher,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Opened(
            driver = driver,
            journal = journal,
            dataSource = dataSource,
            store =
                SharedThreadStore(
                    dataSource = dataSource,
                    scope = scope,
                    diagnosticsLogger = diagnostics?.let { lines -> { line: String -> lines += line } },
                ),
            scope = scope,
        )
    }

    private fun openDatabase(driver: JdbcSqliteDriver): RaftSqlDatabase {
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
            RaftDatabase.Schema.create(driver).value
            driver.execute(
                identifier = null,
                sql = "PRAGMA user_version = ${RaftDatabase.Schema.version}",
                parameters = 0,
                binders = null,
            )
        }
        return RaftSqlDatabase(driver)
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

    /**
     * Feedback (DM with @PM, 11-04): a send that failed on 11-02 09:20 stayed under every message
     * that came after it, days later. A failed send sits where it was sent, newer messages land
     * below it, and that holds after a restart; Retry sends it again as a new message, at the bottom.
     */
    @Test
    fun failedSendKeepsItsPlaceAcrossRestartAndRetryMovesItToTheBottom() = runTest {
        withDatabase { path ->
            val identity = identity()
            val routePayload = basePayload()
            val failedLocalId = "local-send-failed-chronological"
            val first =
                open(path, FakeThreadApi(bodyText = datedPageResponse("m10" to "2026-11-02T09:00:00Z")), identity)
            first.store.accept(SharedThreadIntent.Start(routePayload))
            val route = first.store.state.value.payload.resolveThreadRouteIdentity()
            assertTrue(
                first.store.admitLocalSend(
                    message = pendingMessage(failedLocalId, "random-chronological", createdAt = epoch("2026-11-02T09:20:00Z")),
                    expectedProvenance = requireNotNull(first.dataSource.currentReplyCacheProvenance()),
                    expectedRoute = route
                )
            )
            assertEquals(listOf("m10", failedLocalId), first.store.state.value.payload.messages.map { it.id })
            assertTrue(
                first.journal.markFailed(identity.toProvenance(), route, failedLocalId, "thread_send_network", "offline")
            )
            first.scope.cancel()
            first.driver.close()

            // Restart days later: the conversation moved on.
            val second =
                open(
                    path,
                    FakeThreadApi(
                        bodyText =
                            datedPageResponse(
                                "m10" to "2026-11-02T09:00:00Z",
                                "m11" to "2026-11-03T10:00:00Z",
                                "m12" to "2026-11-04T02:14:00Z",
                            )
                    ),
                    identity
                )
            second.store.accept(SharedThreadIntent.Start(routePayload))
            val restored = second.store.state.value.payload.messages
            assertEquals(listOf("m10", failedLocalId, "m11", "m12"), restored.map { it.id })
            val failed = restored.single { it.id == failedLocalId }
            assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)

            // Retry with a device clock a minute behind the newest message: still the bottom row,
            // stamped with the retry's time (not 11-02 09:20), and the journal keeps that order.
            val retryLabel = "2026-11-04T02:13:00Z"
            assertTrue(
                second.store.admitLocalSend(
                    message =
                        failed.restampedForLocalSendRetry(retryLabel).copy(
                            deliveryState = ThreadMessageDeliverySending,
                            pendingSendErrorCode = null,
                            pendingSendErrorMessage = null
                        ),
                    expectedProvenance = requireNotNull(second.dataSource.currentReplyCacheProvenance()),
                    expectedRoute = route
                )
            )
            val afterRetry = second.store.state.value.payload.messages
            assertEquals(listOf("m10", "m11", "m12", failedLocalId), afterRetry.map { it.id })
            val retried = afterRetry.last()
            assertEquals(retryLabel, retried.timestampLabel)
            assertEquals(ThreadMessageDeliverySending, retried.deliveryState)
            val journaled = second.journal.restore(identity.toProvenance(), route).single { it.id == failedLocalId }
            assertTrue(
                requireNotNull(journaled.createdAtEpochMillis) >= epoch("2026-11-04T02:14:00Z"),
                "the retry is journaled after the newest message: ${journaled.createdAtEpochMillis}"
            )
            second.scope.cancel()
            second.driver.close()
        }
    }

    private fun epoch(iso: String): Long = requireNotNull(parseRaftTimestampEpochMillisOrNull(iso))

    /** A latest page whose messages carry their own creation times, oldest first (seq follows order). */
    private fun datedPageResponse(vararg messages: Pair<String, String>): String =
        messages.withIndex().joinToString(
            prefix = """{"historyLimited": false, "messages": [""",
            postfix = "]}",
        ) { (index, message) ->
            """
            {
              "id": "${message.first}",
              "seq": ${10 + index},
              "channelId": "channel-1",
              "content": "${message.first}",
              "createdAt": "${message.second}",
              "senderId": "u2",
              "senderName": "PM",
              "senderType": "agent",
              "messageType": "chat"
            }
            """.trimIndent()
        }

    private fun basePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "integration-thread",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun pendingMessage(
        localId: String,
        randomId: String,
        createdAt: Long? = null,
        mentions: List<build.raft.app.data.ThreadSendMessageMention> = emptyList(),
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = localId,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = "pending body",
            threadChannelId = "channel-1",
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = "pending body",
                    streamChannelId = "channel-1",
                    randomId = randomId,
                    mentions = mentions,
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

    /**
     * Canonical `POST /api/v2/messages` answer (routes/messages.ts `createHumanMessage(…, "v2")`):
     * the message always wrapped; a replayed randomId carries no sender-only extras.
     */
    private fun sentMessageV2Response(randomId: String): String =
        """{"message": ${sentMessageResponse(randomId)}}"""

    /** The created message row (a list row, and the `message` of a send answer). */
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

    private fun latestPageWithDelivered(randomId: String): String =
        """
        {
          "historyLimited": false,
          "messages": [
            {
              "id": "m1",
              "seq": 10,
              "channelId": "channel-1",
              "content": "earlier",
              "createdAt": "2026-08-24T23:59:00Z",
              "senderId": "u2",
              "senderName": "Ruby",
              "senderType": "user",
              "messageType": "chat"
            },
            ${sentMessageResponse(randomId)}
          ]
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
