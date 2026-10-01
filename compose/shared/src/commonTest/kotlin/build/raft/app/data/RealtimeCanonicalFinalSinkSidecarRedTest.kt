package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitOutcome
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeCanonicalFinalSinkSidecarRedTest {

    @Test
    fun steadyFlagOffAndFlagOnNoncanonicalUseRealLegacyGateWithoutConstructingSidecar() = runBlocking {
        listOf(false to true, true to false).forEachIndexed { index, (enabled, canonical) ->
            val grantA = grant("lease-$index", "server-a", "principal-a", 1L, "family-a", "epoch-a")
            val grantB = grant("lease-$index", "server-a", "principal-a", 2L, "family-b", "epoch-b")
            val authority = FakeGrantAuthority(grantA)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val legacyEntered = CompletableDeferred<Unit>()
            val releaseLegacy = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val lazy =
                RealtimeCanonicalFinalSinkLazyRouter(
                    runIfCurrentGrant = authority::runIfCurrent,
                    sidecarFactory = {
                        RealtimeCanonicalFinalSinkSidecar(
                            scope = scope,
                            capacity = 1,
                            runIfCurrentGrant = authority::runIfCurrent,
                        )
                    },
                )
            val accept =
                async {
                    lazy.accept(
                        preflightEnabled = enabled,
                        hasDecodedCanonical = canonical,
                        grant = grantA,
                        commit = { error("legacy route must not call T3 commit") },
                        envelope = { error("legacy route must not create an envelope") },
                        legacyDirect = {
                            order += "legacy-enter"
                            legacyEntered.complete(Unit)
                            releaseLegacy.await()
                            order += "legacy-exit"
                        },
                    )
                }
            legacyEntered.await()
            val rotated = CompletableDeferred<Unit>()
            val rotate = launch {
                authority.rotate(grantB)
                rotated.complete(Unit)
            }
            yield()
            val rotatedBeforeLegacyExit = rotated.isCompleted
            releaseLegacy.complete(Unit)

            assertTrue(accept.await())
            rotate.join()
            assertFalse(rotatedBeforeLegacyExit)
            assertEquals(listOf("legacy-enter", "legacy-exit"), order)
            assertEquals(0, lazy.debugSnapshot().constructionCount)
            assertEquals(null, lazy.debugSnapshot().sidecar)
            scope.cancel()
        }
    }

    @Test
    fun disabledCommitReleasesReservationAndRunsLegacyWithoutPublishing() = runBlocking {
        val grantA = grant("lease-disabled", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-disabled", "server-a", "principal-a", 2L, "family-b", "epoch-b")
        val authority = FakeGrantAuthority(grantA)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val legacyEntered = CompletableDeferred<Unit>()
        val releaseLegacy = CompletableDeferred<Unit>()
        var legacyCalls = 0
        val lazy =
            RealtimeCanonicalFinalSinkLazyRouter(
                runIfCurrentGrant = authority::runIfCurrent,
                sidecarFactory = {
                    RealtimeCanonicalFinalSinkSidecar(
                        scope = scope,
                        capacity = 1,
                        runIfCurrentGrant = authority::runIfCurrent,
                    )
                },
            )
        val accepted =
            async {
                lazy.accept(
                    preflightEnabled = true,
                    hasDecodedCanonical = true,
                    grant = grantA,
                    commit = { CurrentGrantMessageShadowCommitOutcome.Disabled },
                    envelope = { router ->
                        envelope(
                            grantA,
                            "disabled",
                            messageEvent("disabled", 1L),
                            router.captureRegistrations(grantA.scopeLeaseId, grantA.serverId),
                        )
                    },
                    legacyDirect = {
                        legacyEntered.complete(Unit)
                        releaseLegacy.await()
                        legacyCalls += 1
                    },
                )
            }
        legacyEntered.await()
        val rotated = CompletableDeferred<Unit>()
        val rotate = launch {
            authority.rotate(grantB)
            rotated.complete(Unit)
        }
        yield()
        val rotatedBeforeLegacyExit = rotated.isCompleted
        releaseLegacy.complete(Unit)

        assertTrue(accepted.await())
        rotate.join()
        assertFalse(rotatedBeforeLegacyExit)
        assertEquals(1, legacyCalls)
        assertEquals(
            RealtimeCanonicalFinalSinkDebugSnapshot(
                reserveCount = 1,
                publishCount = 0,
                releaseCount = 1,
                outstandingReservations = 0,
                activeWorkerCount = 0,
                workerKeyCount = 0,
                coreCursorWriteCount = 0,
                registrationCount = 0,
                workerScopeCancellationCount = 0,
                latestReservationState = RealtimeCanonicalFinalSinkReservationState.Terminal,
            ),
            lazy.debugSnapshot().sidecar,
        )
        assertEquals(1, lazy.debugSnapshot().constructionCount)
        scope.cancel()
    }

    @Test
    fun delayedOldUnregisterCannotDeleteSuccessorRegistration() = runBlocking {
        val fixture = fixture()
        val slot = fixture.appDomainSlot()
        val first = fixture.sidecar.register(slot) { fixture.deliveries += "first" }
        val successor = fixture.sidecar.register(slot) { fixture.deliveries += "successor" }

        assertFalse(fixture.sidecar.unregister(first), "old exact handle must not remove its replacement")
        val receipt = fixture.publish(fixture.grantA, "aba", listOf(successor))
        fixture.dispatcher.drain()

        assertTrue(receipt.await())
        assertEquals(listOf("successor"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun repeatedReplacementKeepsOnlyCurrentRegistrationInRegistry() {
        val fixture = fixture()
        repeat(128) { index ->
            fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "sink-$index" }
            assertEquals(1, fixture.sidecar.debugSnapshot().registrationCount)
        }
        val current = fixture.sidecar.captureRegistrations(fixture.grantA.scopeLeaseId, fixture.grantA.serverId).single()
        assertTrue(fixture.sidecar.unregister(current.handle))
        assertEquals(0, fixture.sidecar.debugSnapshot().registrationCount)
        fixture.close()
    }

    @Test
    fun defaultRegistrationIdentityIsOpaqueAndOldHandleCannotDeleteSuccessor() = runBlocking {
        val grant = grant("lease-default-id", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val authority = FakeGrantAuthority(grant)
        val dispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val deliveries = mutableListOf<String>()
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 2,
                runIfCurrentGrant = authority::runIfCurrent,
            )
        val slot = slot(grant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)
        val first = sidecar.register(slot) { deliveries += "first" }
        val successor = sidecar.register(slot) { deliveries += "successor" }
        val oldRemoved = sidecar.unregister(first)
        val captured = sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
        val reservation = sidecar.reserve(grant)
        val receipt =
            sidecar.publishReserved(
                reservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grant, "default-id", messageEvent("default-id", 1L), captured),
            )
        dispatcher.drain()
        val result = receipt.await()
        scope.cancel()

        assertEquals(
            listOf(true, false, listOf(successor), true, listOf("successor")),
            listOf(first != successor, oldRemoved, captured.map { it.handle }, result, deliveries),
            "default registration identity must be opaque/exact so delayed old dispose cannot delete its successor",
        )
    }

    @Test
    fun mixedSurfaceReplayRepairsReplacementWithoutDuplicatingSuccessfulThread() = runBlocking {
        val fixture = fixture()
        val appOne = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "app-one" }
        val thread = fixture.sidecar.register(fixture.threadCoreSlot()) { fixture.deliveries += "thread" }
        val firstReceipt = fixture.publish(fixture.grantA, "same-raw", listOf(appOne, thread))
        val appTwo = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "app-two" }

        fixture.dispatcher.drain()
        assertTrue(firstReceipt.await())
        val replayReceipt = fixture.publish(fixture.grantA, "same-raw", listOf(appTwo, thread))
        fixture.dispatcher.drain()
        assertTrue(replayReceipt.await())

        assertEquals(
            listOf("thread", "app-two"),
            fixture.deliveries,
            "the stable Thread surface is delivered once while the replacement AppShell surface is repaired",
        )
        fixture.close()
    }

    @Test
    fun appShellDomainAndHomeUnreadDoNotConsumeEachOthersDeliveryKey() = runBlocking {
        val fixture = fixture()
        val domain = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "domain" }
        val unread = fixture.sidecar.register(fixture.appUnreadSlot()) { fixture.deliveries += "unread" }

        val domainReceipt = fixture.publish(fixture.grantA, "same-raw", listOf(domain))
        fixture.dispatcher.drain()
        assertTrue(domainReceipt.await())
        val unreadReceipt = fixture.publish(fixture.grantA, "same-raw", listOf(unread))
        fixture.dispatcher.drain()
        assertTrue(unreadReceipt.await())

        assertEquals(listOf("domain", "unread"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun allCapturedHandlesInvalidLeavesSidecarCursorUntouchedAfterT3Advance() = runBlocking {
        val fixture = fixture()
        val old = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "old" }
        fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "new" }

        val receipt = fixture.publish(fixture.grantA, "no-current-handle", listOf(old))
        fixture.dispatcher.drain()

        assertTrue(receipt.await())
        assertEquals(emptyList(), fixture.deliveries)
        assertEquals(listOf("principal-a"), fixture.t3Cursor.partitions.map { it.principalId })
        assertEquals(emptyList(), fixture.oldSidecarCursorPartitions)
        assertEquals(0, fixture.sidecar.debugSnapshot().coreCursorWriteCount)
        fixture.close()
    }

    @Test
    fun queuedFirstGenerationACannotApplyAfterBThenNewA() = runBlocking {
        val fixture = fixture()
        val firstA = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "first-a" }
        val oldReceipt = fixture.publish(fixture.grantA, "a-b-a", listOf(firstA))

        fixture.authority.rotate(fixture.grantB)
        fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "b" }
        fixture.authority.rotate(fixture.grantA3)
        val thirdA = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "third-a" }
        fixture.sidecar.invalidateExcept(setOf(fixture.grantA3))
        fixture.dispatcher.drain()
        val newReceipt = fixture.publish(fixture.grantA3, "a-b-a", listOf(thirdA))
        fixture.dispatcher.drain()

        assertFalse(oldReceipt.await())
        assertTrue(newReceipt.await())
        assertEquals(listOf("third-a"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun queuedItemAfterLeaseReleaseIsZeroWriteAndNewLeaseDoesNotReviveIt() = runBlocking {
        val fixture = fixture()
        val oldHandle = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "old" }
        val oldReceipt = fixture.publish(fixture.grantA, "released", listOf(oldHandle))

        fixture.authority.release()
        fixture.sidecar.invalidateExcept(emptySet())
        assertTrue(fixture.sidecar.unregister(oldHandle))
        val newGrant = grant("lease-new", "server-b", "principal-a", 2L, "family-new", "epoch-new")
        fixture.authority.rotate(newGrant)
        val newHandle =
            fixture.sidecar.register(slot(newGrant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
                fixture.deliveries += "new"
            }
        assertFalse(fixture.sidecar.unregister(oldHandle), "delayed old-group dispose must be exact false")
        fixture.dispatcher.drain()
        val newReceipt = fixture.publish(newGrant, "released", listOf(newHandle))
        fixture.dispatcher.drain()

        assertFalse(oldReceipt.await())
        assertTrue(newReceipt.await())
        assertEquals(listOf("new"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun samePrincipalReconnectRejectsOldFamilyButKeepsNewFamilyLive() = runBlocking {
        val fixture = fixture()
        val oldHandle = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "old" }
        val oldReceipt = fixture.publish(fixture.grantA, "reconnect", listOf(oldHandle))

        fixture.authority.rotate(fixture.grantA3)
        val newHandle = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "new" }
        fixture.sidecar.invalidateExcept(setOf(fixture.grantA3))
        fixture.dispatcher.drain()
        val newReceipt = fixture.publish(fixture.grantA3, "reconnect", listOf(newHandle))
        fixture.dispatcher.drain()

        assertFalse(oldReceipt.await())
        assertTrue(newReceipt.await())
        assertEquals(listOf("new"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun differentServersAndThreadCoreFeatureSlotsDoNotDeleteEachOther() {
        val fixture = fixture()
        val core = fixture.sidecar.register(fixture.threadCoreSlot()) {}
        fixture.sidecar.register(
            slot(fixture.grantA, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a"))
        ) {}
        val otherServerGrant =
            grant("lease-a", "server-b", "principal-a", 1L, "family-a", "epoch-other-server")
        fixture.sidecar.register(
            slot(otherServerGrant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)
        ) {}

        assertTrue(fixture.sidecar.unregister(core))
        assertEquals(
            setOf(RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")),
            fixture.sidecar.captureRegistrations(fixture.grantA.scopeLeaseId, fixture.grantA.serverId)
                .map { it.handle.slot.surfaceKey }
                .toSet(),
        )
        assertEquals(1, fixture.sidecar.captureRegistrations("lease-a", "server-b").size)
        fixture.close()
    }

    @Test
    fun principalBLowerSequenceAndSameRawKeyAreNotConsumedByPrincipalA() = runBlocking {
        val fixture = fixture()
        val handle = fixture.sidecar.register(fixture.appDomainSlot()) { event ->
            fixture.deliveries += (event as MessageNew).message.body
        }
        val first = fixture.publish(fixture.grantA, "same-key", listOf(handle), event = messageEvent("a", 40L))
        fixture.dispatcher.drain()
        assertTrue(first.await())

        fixture.authority.rotate(fixture.grantB)
        val second = fixture.publish(fixture.grantB, "same-key", listOf(handle), event = messageEvent("b", 1L))
        fixture.dispatcher.drain()
        assertTrue(second.await())

        assertEquals(listOf("a", "b"), fixture.deliveries)
        assertEquals(listOf("principal-a", "principal-b"), fixture.t3Cursor.partitions.map { it.principalId })
        assertEquals(emptyList(), fixture.oldSidecarCursorPartitions)
        assertEquals(0, fixture.sidecar.debugSnapshot().coreCursorWriteCount)
        fixture.close()
    }

    @Test
    fun deliveredLedgerIsBoundedIndependentlyPerPrincipalPartitionAndSurface() = runBlocking {
        val dispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val grantA = grant("lease-ledger", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-ledger", "server-a", "principal-b", 2L, "family-b", "epoch-b")
        val deliveries = mutableListOf<String>()
        var registrationId = 0
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 32,
                runIfCurrentGrant = { _, block -> block(); true },
                registrationIdFactory = { "ledger-${++registrationId}" },
                deliveredLedgerLimitPerSurface = 2,
            )
        val domain =
            sidecar.register(slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) { event ->
                deliveries += "domain:${(event as MessageNew).message.body}"
            }
        val unread =
            sidecar.register(slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread)) { event ->
                deliveries += "unread:${(event as MessageNew).message.body}"
            }

        suspend fun publish(
            grant: RealtimeGrantRecord,
            surface: RealtimeCanonicalFinalSinkSurfaceKey,
            handle: RealtimeCanonicalFinalSinkRegistrationHandle,
            key: String,
        ) {
            val captured =
                sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
                    .filter { it.handle == handle }
            val reservation = sidecar.reserve(grant)
            val receipt =
                sidecar.publishReserved(
                    reservation,
                    CurrentGrantMessageShadowCommitOutcome.Committed,
                    envelope(grant, key, messageEvent(key, 1L), captured),
                )
            dispatcher.drain()
            assertTrue(receipt.await(), "ledger setup must final-apply $surface/$key")
        }

        listOf(
            Triple(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain, domain),
            Triple(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread, unread),
            Triple(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain, domain),
            Triple(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread, unread),
        ).forEachIndexed { ledgerIndex, (grant, surface, handle) ->
            repeat(3) { keyIndex ->
                publish(grant, surface, handle, "ledger-$ledgerIndex-key-$keyIndex")
            }
        }
        repeat(4) { ledgerIndex ->
            val (grant, surface, handle) =
                listOf(
                    Triple(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain, domain),
                    Triple(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread, unread),
                    Triple(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain, domain),
                    Triple(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread, unread),
                )[ledgerIndex]
            publish(grant, surface, handle, "ledger-$ledgerIndex-key-0")
        }

        fun ledgerKey(
            grant: RealtimeGrantRecord,
            surface: RealtimeCanonicalFinalSinkSurfaceKey,
        ) = RealtimeCanonicalFinalSinkDeliveryLedgerKey(
            partition =
                DurablePartitionKey(
                    serverId = grant.serverId,
                    principalId = grant.principalId,
                    consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                    environmentId = grant.environmentId,
                ),
            surfaceKey = surface,
        )

        val expectedSizes =
            mapOf(
                ledgerKey(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) to 2,
                ledgerKey(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread) to 2,
                ledgerKey(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) to 2,
                ledgerKey(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread) to 2,
            )
        val snapshot = sidecar.debugSnapshot()
        val actual =
            listOf(
                snapshot.deliveredLedgerLimitPerSurface,
                snapshot.deliveredLedgerSizes,
                deliveries.size,
            )
        sidecar.unregister(domain)
        sidecar.unregister(unread)
        scope.cancel()

        assertEquals(
            listOf(2, expectedSizes, 16),
            actual,
            "each principal-partition/surface ledger must cap independently; evicted raw keys may replay at-least-once",
        )
    }

    @Test
    fun reservedOldGrantInvalidationMakesLaterPublishFailAndReleasesExactlyOnce() = runBlocking {
        val fixture = fixture(capacity = 1)
        val handle = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "stale" }
        val reservation = fixture.sidecar.reserve(fixture.grantA)

        fixture.authority.rotate(fixture.grantB)
        fixture.sidecar.invalidateExcept(setOf(fixture.grantB))
        val receipt =
            fixture.sidecar.publishReserved(
                reservation = reservation,
                outcome = CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope =
                    fixture.envelope(
                        fixture.grantA,
                        "reserved-stale",
                        fixture.sidecar.captureRegistrations(fixture.grantA.scopeLeaseId, fixture.grantA.serverId)
                            .filter { it.handle == handle },
                    ),
            )
        fixture.dispatcher.drain()

        assertFalse(receipt.await())
        assertEquals(emptyList(), fixture.deliveries)
        assertEquals(1, fixture.sidecar.debugSnapshot().releaseCount)
        assertEquals(0, fixture.sidecar.debugSnapshot().outstandingReservations)
        fixture.close()
    }

    @Test
    fun staleBeforeClaimTerminatesReservationExactlyOnceWithoutPublishing() = runBlocking {
        val grantA = grant("lease-stale", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-stale", "server-a", "principal-a", 2L, "family-b", "epoch-b")
        val authority = FakeGrantAuthority(grantB)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var sidecar: RealtimeCanonicalFinalSinkSidecar
        val lazy =
            RealtimeCanonicalFinalSinkLazyRouter(
                runIfCurrentGrant = authority::runIfCurrent,
                sidecarFactory = {
                    RealtimeCanonicalFinalSinkSidecar(
                        scope = scope,
                        capacity = 1,
                        runIfCurrentGrant = authority::runIfCurrent,
                    ).also { sidecar = it }
                },
            )
        var commitCalls = 0
        var envelopeCalls = 0
        val accepted =
            lazy.accept(
                preflightEnabled = true,
                hasDecodedCanonical = true,
                grant = grantA,
                commit = {
                    commitCalls += 1
                    CurrentGrantMessageShadowCommitOutcome.Committed
                },
                envelope = {
                    envelopeCalls += 1
                    envelope(grantA, "stale-before-claim", messageEvent("stale", 1L), emptyList())
                },
                legacyDirect = { error("stale candidate must not run legacy") },
            )
        val snapshot = sidecar.debugSnapshot()
        scope.cancel()

        assertEquals(
            listOf(
                false,
                0,
                0,
                1,
                0,
                0,
                RealtimeCanonicalFinalSinkReservationState.Terminal,
            ),
            listOf(
                accepted,
                commitCalls,
                envelopeCalls,
                snapshot.releaseCount,
                snapshot.outstandingReservations,
                snapshot.publishCount,
                snapshot.latestReservationState,
            ),
            "stale-before-claim must terminal the reserved token exactly once without commit/envelope/publish",
        )
    }

    @Test
    fun invalidateBetweenPublishedCasAndQueueRegistrationCompletesReceiptAndLeaksNothing() = runBlocking {
        val grantA = grant("lease-publish-gap", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-publish-gap", "server-a", "principal-a", 2L, "family-b", "epoch-b")
        val authority = FakeGrantAuthority(grantA)
        val dispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val publishedGapEntered = CompletableDeferred<Unit>()
        val releasePublishedGap = CompletableDeferred<Unit>()
        val deliveries = mutableListOf<String>()
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 1,
                runIfCurrentGrant = authority::runIfCurrent,
                workerDispatcherForGrant = { dispatcher },
                afterReservationPublished = {
                    publishedGapEntered.complete(Unit)
                    releasePublishedGap.await()
                },
            )
        val handle =
            sidecar.register(slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
                deliveries += "old"
            }
        val publisher =
            async {
                val reservation = sidecar.reserve(grantA)
                sidecar.publishReserved(
                    reservation,
                    CurrentGrantMessageShadowCommitOutcome.Committed,
                    envelope(
                        grantA,
                        "publish-gap",
                        messageEvent("publish-gap", 1L),
                        sidecar.captureRegistrations(grantA.scopeLeaseId, grantA.serverId)
                            .filter { it.handle == handle },
                    ),
                )
            }
        publishedGapEntered.await()

        authority.rotate(grantB)
        sidecar.invalidateExcept(setOf(grantB))
        val afterInvalidation = sidecar.debugSnapshot()
        val successorReservation = async { sidecar.reserve(grantB) }
        val successor = successorReservation.await()
        releasePublishedGap.complete(Unit)
        val oldReceipt = publisher.await()
        dispatcher.drain()
        val oldResult = if (oldReceipt.isCompleted) runCatching { oldReceipt.await() }.getOrNull() else null
        sidecar.abandonReserved(successor)
        val terminal = sidecar.debugSnapshot()
        sidecar.unregister(handle)
        scope.cancel()

        assertEquals(
            listOf(
                listOf(1, 0, 0, 0),
                false,
                emptyList<String>(),
                listOf(2, 0, 0, 0),
            ),
            listOf(
                listOf(
                    afterInvalidation.releaseCount,
                    afterInvalidation.outstandingReservations,
                    afterInvalidation.workerKeyCount,
                    afterInvalidation.publishedItemCount,
                ),
                oldResult,
                deliveries,
                listOf(
                    terminal.releaseCount,
                    terminal.outstandingReservations,
                    terminal.workerKeyCount,
                    terminal.publishedItemCount,
                ),
            ),
            "Published invalidation must complete false, release once, leak no item/worker/permit, and let a successor reserve",
        )
    }

    @Test
    fun commitOrEnvelopeFailureAndCancellationTerminalReservationExactlyOnce() = runBlocking {
        val cases =
            listOf(
                Triple("commit-exception", true, IllegalStateException("commit-exception")),
                Triple("commit-cancellation", true, kotlinx.coroutines.CancellationException("commit-cancellation")),
                Triple("envelope-exception", false, IllegalStateException("envelope-exception")),
                Triple("envelope-cancellation", false, kotlinx.coroutines.CancellationException("envelope-cancellation")),
            )
        val actual =
            cases.mapIndexed { index, (label, failCommit, thrown) ->
                val grant =
                    grant("lease-failure-$index", "server-a", "principal-a", 1L, "family-a", "epoch-a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                lateinit var sidecar: RealtimeCanonicalFinalSinkSidecar
                val lazy =
                    RealtimeCanonicalFinalSinkLazyRouter(
                        runIfCurrentGrant = { _, block -> block(); true },
                        sidecarFactory = {
                            RealtimeCanonicalFinalSinkSidecar(
                                scope = scope,
                                capacity = 1,
                                runIfCurrentGrant = { _, block -> block(); true },
                            ).also { sidecar = it }
                        },
                    )
                val failure =
                    runCatching {
                        lazy.accept(
                            preflightEnabled = true,
                            hasDecodedCanonical = true,
                            grant = grant,
                            commit = {
                                if (failCommit) throw thrown
                                CurrentGrantMessageShadowCommitOutcome.Committed
                            },
                            envelope = {
                                if (!failCommit) throw thrown
                                envelope(grant, label, messageEvent(label, 1L), emptyList())
                            },
                            legacyDirect = { error("$label must not run legacy") },
                        )
                    }.exceptionOrNull()
                val snapshot = sidecar.debugSnapshot()
                scope.cancel()
                listOf(
                    failure === thrown,
                    snapshot.releaseCount,
                    snapshot.outstandingReservations,
                    snapshot.publishCount,
                    snapshot.workerKeyCount,
                    snapshot.latestReservationState,
                )
            }

        assertEquals(
            List(4) {
                listOf(
                    true,
                    1,
                    0,
                    0,
                    0,
                    RealtimeCanonicalFinalSinkReservationState.Terminal,
                )
            },
            actual,
            "commit/envelope exception or cancellation must terminal the reserved token exactly once",
        )
    }

    @Test
    fun reservationAndEnvelopeGrantMismatchFailsClosedAndReleasesExactlyOnce() = runBlocking {
        val fixture = fixture(capacity = 1)
        val handle = fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "mismatch" }
        val captured =
            fixture.sidecar.captureRegistrations(fixture.grantA.scopeLeaseId, fixture.grantA.serverId)
                .filter { it.handle == handle }
        val reservation = fixture.sidecar.reserve(fixture.grantA)
        val receipt =
            fixture.sidecar.publishReserved(
                reservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                fixture.envelope(fixture.grantB, "grant-mismatch", captured),
            )
        fixture.dispatcher.drain()
        val result = runCatching { receipt.await() }.getOrNull()
        val snapshot = fixture.sidecar.debugSnapshot()
        fixture.close()

        assertEquals(
            listOf(
                false,
                emptyList<String>(),
                0,
                1,
                0,
                0,
                RealtimeCanonicalFinalSinkReservationState.Terminal,
            ),
            listOf(
                result,
                fixture.deliveries,
                snapshot.coreCursorWriteCount,
                snapshot.releaseCount,
                snapshot.outstandingReservations,
                snapshot.workerKeyCount,
                reservation.state.value,
            ),
            "reservation/envelope exact-grant mismatch must fail closed with W0 and one terminal release",
        )
    }

    @Test
    fun callerCancellationAfterPublishDoesNotCancelSharedItem() = runBlocking {
        val fixture = fixture()
        val handle = fixture.sidecar.register(fixture.appDomainSlot()) { event ->
            fixture.deliveries += (event as MessageNew).message.body
        }
        val receipt = fixture.publish(
            fixture.grantA,
            "caller-cancel-1",
            listOf(handle),
            messageEvent("first", 1L),
        )
        val caller = launch { receipt.await() }

        caller.cancelAndJoin()
        val next = fixture.publish(
            fixture.grantA,
            "caller-cancel-2",
            listOf(handle),
            messageEvent("second", 2L),
        )
        fixture.dispatcher.drain()

        assertTrue(receipt.isCompleted)
        assertTrue(next.await())
        assertEquals(listOf("first", "second"), fixture.deliveries)
        fixture.close()
    }

    @Test
    fun concurrentFirstCanonicalAcceptsShareOneLazySidecarAndGlobalCapacity() = runBlocking {
        val grant = grant("lease-lazy-race", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val authority = FakeGrantAuthority(grant)
        val dispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val created = mutableListOf<RealtimeCanonicalFinalSinkSidecar>()
        val hookMutex = Mutex()
        var missingObservers = 0
        val bothObservedMissing = CompletableDeferred<Unit>()
        val releaseMissingObservers = CompletableDeferred<Unit>()
        val lazy =
            RealtimeCanonicalFinalSinkLazyRouter(
                runIfCurrentGrant = authority::runIfCurrent,
                sidecarFactory = {
                    RealtimeCanonicalFinalSinkSidecar(
                        scope = scope,
                        capacity = 2,
                        runIfCurrentGrant = authority::runIfCurrent,
                        workerDispatcherForGrant = { dispatcher },
                    ).also { created += it }
                },
                afterMissingSidecarObserved = {
                    hookMutex.withLock {
                        missingObservers += 1
                        if (missingObservers == 2) bothObservedMissing.complete(Unit)
                    }
                    releaseMissingObservers.await()
                },
            )

        fun accept(key: String) =
            async {
                lazy.accept(
                    preflightEnabled = true,
                    hasDecodedCanonical = true,
                    grant = grant,
                    commit = { CurrentGrantMessageShadowCommitOutcome.Committed },
                    envelope = { sidecar ->
                        envelope(
                            grant,
                            key,
                            messageEvent(key, 1L),
                            sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId),
                        )
                    },
                    legacyDirect = { error("canonical race must not run legacy") },
                )
            }

        val first = accept("lazy-race-1")
        val second = accept("lazy-race-2")
        bothObservedMissing.await()
        releaseMissingObservers.complete(Unit)
        while (created.sumOf { it.debugSnapshot().publishCount } < 2) yield()
        val beforeInvalidation = lazy.debugSnapshot()
        created.forEach { it.invalidateExcept(emptySet()) }
        val results = listOf(first.await(), second.await())
        dispatcher.drain()
        val terminal = lazy.debugSnapshot()
        scope.cancel()

        assertEquals(
            listOf(
                1,
                1,
                2,
                listOf(2, 2, 2, 1),
                listOf(false, false),
                listOf(2, 0, 0, 0),
            ),
            listOf(
                created.size,
                beforeInvalidation.constructionCount,
                missingObservers,
                listOf(
                    beforeInvalidation.sidecar?.reserveCount,
                    beforeInvalidation.sidecar?.publishCount,
                    beforeInvalidation.sidecar?.outstandingReservations,
                    beforeInvalidation.sidecar?.workerKeyCount,
                ),
                results,
                listOf(
                    terminal.sidecar?.releaseCount,
                    terminal.sidecar?.outstandingReservations,
                    terminal.sidecar?.workerKeyCount,
                    terminal.sidecar?.publishedItemCount,
                ),
            ),
            "two simultaneous first canonical accepts must share one sidecar, one capacity pool, and one debug/invalidate view",
        )
    }

    @Test
    fun fullOldCapacityIsPrunedWithoutDrainingOldWorkerSoSuccessorCanReserve() = runBlocking {
        val grantA = grant("lease-capacity", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-capacity", "server-a", "principal-a", 2L, "family-b", "epoch-b")
        val authority = FakeGrantAuthority(grantA)
        val oldDispatcher = ManualQueueDispatcher()
        val successorDispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deliveries = mutableListOf<String>()
        var registrationId = 0
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 1,
                runIfCurrentGrant = authority::runIfCurrent,
                registrationIdFactory = { "capacity-${++registrationId}" },
                workerDispatcherForGrant = { grant ->
                    if (grant == grantA) oldDispatcher else successorDispatcher
                },
            )
        val oldHandle =
            sidecar.register(slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
                deliveries += "old"
            }
        val oldCaptured = sidecar.captureRegistrations(grantA.scopeLeaseId, grantA.serverId)
        val oldReservation = sidecar.reserve(grantA)
        val oldReceipt =
            sidecar.publishReserved(
                oldReservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grantA, "capacity-old", messageEvent("old", 1L), oldCaptured),
            )
        assertEquals(1, sidecar.debugSnapshot().outstandingReservations)

        val successorReservation = async { sidecar.reserve(grantB) }
        yield()
        val completedBeforePrune = successorReservation.isCompleted
        authority.rotate(grantB)
        val successorHandle =
            sidecar.register(slot(grantB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
                deliveries += "new"
            }
        sidecar.invalidateExcept(setOf(grantB))
        yield()

        val successor = successorReservation.await()
        val successorCaptured =
            sidecar.captureRegistrations(grantB.scopeLeaseId, grantB.serverId)
                .filter { it.handle == successorHandle }
        val successorReceipt =
            sidecar.publishReserved(
                successor,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grantB, "capacity-successor", messageEvent("new", 2L), successorCaptured),
            )
        successorDispatcher.drain()
        val oldResult = if (oldReceipt.isCompleted) oldReceipt.await() else null
        val successorResult = successorReceipt.await()
        val snapshot = sidecar.debugSnapshot()
        sidecar.unregister(oldHandle)
        sidecar.unregister(successorHandle)
        scope.cancel()

        assertFalse(completedBeforePrune, "capacity wait must suspend outside the registry gate")
        assertEquals(false, oldResult, "old A receipt must terminal false without draining old A")
        assertTrue(successorResult, "B committed worker must final-apply on its independent lane")
        assertEquals(listOf("new"), deliveries)
        assertEquals(2, snapshot.releaseCount, "old prune and B terminal each release exactly once")
        assertEquals(0, snapshot.outstandingReservations)
    }

    @Test
    fun disposeBeforeClaimIsZeroButDisposeAfterClaimAllowsLinearizedDelivery() = runBlocking {
        val beforeFixture = fixture()
        val old = beforeFixture.sidecar.register(beforeFixture.appDomainSlot()) { beforeFixture.deliveries += "old" }
        beforeFixture.sidecar.register(beforeFixture.appDomainSlot()) { beforeFixture.deliveries += "new" }
        val beforeReceipt = beforeFixture.publish(beforeFixture.grantA, "before-claim", listOf(old))
        beforeFixture.dispatcher.drain()
        assertTrue(beforeReceipt.await())
        assertEquals(emptyList(), beforeFixture.deliveries)
        beforeFixture.close()

        val gate = FakeGrantAuthority(grant("lease-a", "server-a", "principal-a", 1L, "family-a", "epoch-a"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deliveries = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var id = 0
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 4,
                runIfCurrentGrant = gate::runIfCurrent,
                registrationIdFactory = { "claim-${++id}" },
            )
        val grant = gate.current()
        val slot = slot(grant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)
        val handle =
            sidecar.register(slot) {
                entered.complete(Unit)
                release.await()
                deliveries += "linearized"
            }
        val captured = sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
        val reservation = sidecar.reserve(grant)
        val receipt =
            sidecar.publishReserved(
                reservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grant, "after-claim", messageEvent("after", 1L), captured),
            )
        entered.await()
        sidecar.unregister(handle)
        release.complete(Unit)

        assertTrue(receipt.await())
        assertEquals(listOf("linearized"), deliveries)
        scope.cancel()
    }

    @Test
    fun sameGrantFifoHoldsWhenSecondWorkerCanRunBeforeFirstSinkIsReleased() = runBlocking {
        val grant = grant("lease-fifo", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val authority = FakeGrantAuthority(grant)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val mutations = mutableListOf<String>()
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 4,
                runIfCurrentGrant = authority::runIfCurrent,
                registrationIdFactory = { "fifo" },
            )
        val handle =
            sidecar.register(slot(grant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) { event ->
                val body = (event as MessageNew).message.body
                if (body == "one") {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                mutations += body
            }
        val captured = sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
        val firstReservation = sidecar.reserve(grant)
        val first =
            sidecar.publishReserved(
                firstReservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grant, "fifo-1", messageEvent("one", 1L), captured),
            )
        firstEntered.await()
        val secondReservation = sidecar.reserve(grant)
        val second =
            sidecar.publishReserved(
                secondReservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grant, "fifo-2", messageEvent("two", 2L), captured),
            )
        yield()
        val beforeFirstRelease = mutations.toList()
        releaseFirst.complete(Unit)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(emptyList(), beforeFirstRelease, "E2 must not mutate while E1 final sink is parked")
        assertEquals(listOf("one", "two"), mutations)
        assertTrue(sidecar.unregister(handle))
        scope.cancel()
    }

    @Test
    fun surfaceThrowOrSelfCancellationDoesNotPoisonOtherSurfacesOrNextItem() = runBlocking {
        val actualByFailure =
            listOf<Throwable>(IllegalStateException("sink-failed"), kotlinx.coroutines.CancellationException("sink-ce"))
                .mapIndexed { index, failure ->
                    val fixture = fixture()
                    var throwingCalls = 0
                    val throwing = fixture.sidecar.register(fixture.appDomainSlot()) {
                        throwingCalls += 1
                        throw failure
                    }
                    val healthy = fixture.sidecar.register(fixture.threadCoreSlot()) { fixture.deliveries += "healthy-$index" }
                    val failed = fixture.publish(fixture.grantA, "failure-$index", listOf(throwing, healthy))
                    fixture.dispatcher.drain()
                    assertTrue(failed.isCompleted)

                    fixture.sidecar.register(fixture.appDomainSlot()) { fixture.deliveries += "replacement-$index" }
                    val next = fixture.publishCaptured(
                        fixture.grantA,
                        "failure-$index",
                        fixture.sidecar.captureRegistrations(fixture.grantA.scopeLeaseId, fixture.grantA.serverId),
                    )
                    fixture.dispatcher.drain()
                    assertTrue(next.await())

                    val snapshot = fixture.sidecar.debugSnapshot()
                    listOf(
                        fixture.deliveries.toList(),
                        throwingCalls,
                        snapshot.releaseCount,
                        snapshot.outstandingReservations,
                        snapshot.activeWorkerCount,
                        snapshot.workerKeyCount,
                    ).also {
                        fixture.close()
                    }
                }

        assertEquals(
            listOf(
                listOf(listOf("healthy-0", "replacement-0"), 1, 2, 0, 0, 0),
                listOf(listOf("healthy-1", "replacement-1"), 1, 2, 0, 0, 0),
            ),
            actualByFailure,
            "same raw replay must repair only the failed replacement; healthy sibling stays exactly once and every token terminals",
        )
    }

    @Test
    fun realWorkerScopeCancellationPropagatesInsteadOfBeingIsolatedAsSinkFailure() = runBlocking {
        val grant = grant("lease-worker-cancel", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val authority = FakeGrantAuthority(grant)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 1,
                runIfCurrentGrant = authority::runIfCurrent,
                registrationIdFactory = { "worker-cancel" },
            )
        sidecar.register(slot(grant, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
            entered.complete(Unit)
            never.await()
        }
        val captured = sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
        val reservation = sidecar.reserve(grant)
        val receipt =
            sidecar.publishReserved(
                reservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grant, "worker-cancel", messageEvent("worker-cancel", 1L), captured),
            )
        entered.await()
        scope.cancel()
        val failure = runCatching { receipt.await() }.exceptionOrNull()
        yield()
        val snapshot = sidecar.debugSnapshot()

        assertEquals(
            listOf(
                true,
                1,
                1,
                0,
                0,
                0,
                RealtimeCanonicalFinalSinkReservationState.Terminal,
            ),
            listOf(
                failure is kotlinx.coroutines.CancellationException,
                snapshot.workerScopeCancellationCount,
                snapshot.releaseCount,
                snapshot.outstandingReservations,
                snapshot.activeWorkerCount,
                snapshot.workerKeyCount,
                reservation.state.value,
            ),
            "real worker cancellation must propagate, be classified, and terminal exactly once without leaked capacity",
        )
    }

    @Test
    fun drainedQueueReleasesReservationsAndWorkerKeys() = runBlocking {
        val fixture = fixture()
        val handle = fixture.sidecar.register(fixture.appDomainSlot()) {}
        val receipt = fixture.publish(fixture.grantA, "cleanup", listOf(handle))
        fixture.dispatcher.drain()

        assertTrue(receipt.await())
        assertEquals(0, fixture.sidecar.debugSnapshot().outstandingReservations)
        assertEquals(0, fixture.sidecar.debugSnapshot().activeWorkerCount)
        assertEquals(0, fixture.sidecar.debugSnapshot().workerKeyCount)
        fixture.close()
    }

    @Test
    fun rotateWaitsWhenFinalSinkHasAlreadyEnteredCurrentGrantBlock() = runBlocking {
        val grantA = grant("lease-a", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-a", "server-a", "principal-a", 2L, "family-b", "epoch-b")
        val authority = FakeGrantAuthority(grantA)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rotated = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = 4,
                runIfCurrentGrant = authority::runIfCurrent,
                registrationIdFactory = { "final-linearization" },
            )
        val handle =
            sidecar.register(slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)) {
                entered.complete(Unit)
                release.await()
            }
        val captured = sidecar.captureRegistrations(grantA.scopeLeaseId, grantA.serverId)
        val reservation = sidecar.reserve(grantA)
        val receipt =
            sidecar.publishReserved(
                reservation,
                CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope(grantA, "linearization", messageEvent("linearization", 1L), captured),
            )
        entered.await()
        val rotateJob = launch {
            authority.rotate(grantB)
            rotated.complete(Unit)
        }
        yield()
        val rotatedBeforeRelease = rotated.isCompleted
        release.complete(Unit)
        receipt.await()
        rotateJob.join()

        assertFalse(rotatedBeforeRelease, "auth mutation must wait for an entered final sink")
        scope.cancel()
    }

    private class Fixture(
        val dispatcher: ManualQueueDispatcher,
        val scope: CoroutineScope,
        val authority: FakeGrantAuthority,
        val sidecar: RealtimeCanonicalFinalSinkSidecar,
        val grantA: RealtimeGrantRecord,
        val grantB: RealtimeGrantRecord,
        val grantA3: RealtimeGrantRecord,
        val deliveries: MutableList<String>,
        val t3Cursor: T3DurableCursorFixture,
        val oldSidecarCursorPartitions: MutableList<DurablePartitionKey>,
    ) {
        fun appDomainSlot(): RealtimeCanonicalFinalSinkRegistrationSlot =
            slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain)

        fun appUnreadSlot(): RealtimeCanonicalFinalSinkRegistrationSlot =
            slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread)

        fun threadCoreSlot(): RealtimeCanonicalFinalSinkRegistrationSlot =
            slot(grantA, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("route-a"))

        fun envelope(
            grant: RealtimeGrantRecord,
            eventKey: String,
            registrations: List<RealtimeCanonicalFinalSinkCapturedRegistration> =
                sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId),
            event: RealtimeEvent = messageEvent(eventKey, 1L),
        ): RealtimeCanonicalFinalSinkEnvelope =
            envelope(grant, eventKey, event, registrations)

        suspend fun publish(
            grant: RealtimeGrantRecord,
            eventKey: String,
            handles: List<RealtimeCanonicalFinalSinkRegistrationHandle>,
            event: RealtimeEvent = messageEvent(eventKey, 1L),
        ): RealtimeCanonicalFinalSinkReceipt {
            val reservation = sidecar.reserve(grant)
            t3Cursor.commit(grant)
            val captured =
                sidecar.captureRegistrations(grant.scopeLeaseId, grant.serverId)
                    .filter { registration -> registration.handle in handles }
            return sidecar.publishReserved(
                reservation = reservation,
                outcome = CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope = envelope(grant, eventKey, captured, event),
            )
        }

        suspend fun publishCaptured(
            grant: RealtimeGrantRecord,
            eventKey: String,
            registrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
            event: RealtimeEvent = messageEvent(eventKey, 1L),
        ): RealtimeCanonicalFinalSinkReceipt {
            val reservation = sidecar.reserve(grant)
            t3Cursor.commit(grant)
            return sidecar.publishReserved(
                reservation = reservation,
                outcome = CurrentGrantMessageShadowCommitOutcome.Committed,
                envelope = envelope(grant, eventKey, registrations, event),
            )
        }

        fun close() {
            check(oldSidecarCursorPartitions.isEmpty()) { "T4 sidecar must never write the legacy/core cursor" }
            check(sidecar.debugSnapshot().coreCursorWriteCount == 0)
            scope.cancel()
        }
    }

    private class T3DurableCursorFixture {
        val partitions = mutableListOf<DurablePartitionKey>()

        fun commit(grant: RealtimeGrantRecord) {
            partitions += durablePartition(grant)
        }
    }

    private class FakeGrantAuthority(initial: RealtimeGrantRecord) {
        private val mutex = Mutex()
        private var current: RealtimeGrantRecord? = initial

        suspend fun runIfCurrent(
            grant: RealtimeGrantRecord,
            block: suspend () -> Unit,
        ): Boolean =
            mutex.withLock {
                if (current != grant) return@withLock false
                block()
                true
            }

        suspend fun rotate(next: RealtimeGrantRecord) {
            mutex.withLock { current = next }
        }

        suspend fun release() {
            mutex.withLock { current = null }
        }

        suspend fun current(): RealtimeGrantRecord = mutex.withLock { requireNotNull(current) }
    }

    private class ManualQueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    private fun fixture(capacity: Int = 8): Fixture {
        val dispatcher = ManualQueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val grantA = grant("lease-a", "server-a", "principal-a", 1L, "family-a", "epoch-a")
        val grantB = grant("lease-a", "server-a", "principal-b", 2L, "family-b", "epoch-b")
        val grantA3 = grant("lease-a", "server-a", "principal-a", 3L, "family-c", "epoch-c")
        val authority = FakeGrantAuthority(grantA)
        val deliveries = mutableListOf<String>()
        val t3Cursor = T3DurableCursorFixture()
        val oldSidecarCursorPartitions = mutableListOf<DurablePartitionKey>()
        var registrationId = 0
        val sidecar =
            RealtimeCanonicalFinalSinkSidecar(
                scope = scope,
                capacity = capacity,
                runIfCurrentGrant = authority::runIfCurrent,
                onCoreCursorWrite = { partition, _ -> oldSidecarCursorPartitions += partition },
                registrationIdFactory = { "registration-${++registrationId}" },
            )
        return Fixture(
            dispatcher = dispatcher,
            scope = scope,
            authority = authority,
            sidecar = sidecar,
            grantA = grantA,
            grantB = grantB,
            grantA3 = grantA3,
            deliveries = deliveries,
            t3Cursor = t3Cursor,
            oldSidecarCursorPartitions = oldSidecarCursorPartitions,
        )
    }

    private companion object {
        fun grant(
            leaseId: String,
            serverId: String,
            principalId: String,
            generation: Long,
            familyId: String,
            epochId: String,
        ): RealtimeGrantRecord =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey(scopeLeaseId = leaseId, serverId = serverId),
                principalId = principalId,
                environmentId = "env-test",
                authGeneration = generation,
                connectionFamilyId = familyId,
                connectionEpochId = epochId,
            )

        fun slot(
            grant: RealtimeGrantRecord,
            surface: RealtimeCanonicalFinalSinkSurfaceKey,
        ): RealtimeCanonicalFinalSinkRegistrationSlot =
            RealtimeCanonicalFinalSinkRegistrationSlot(
                scopeLeaseId = grant.scopeLeaseId,
                serverId = grant.serverId,
                surfaceKey = surface,
            )

        fun envelope(
            grant: RealtimeGrantRecord,
            eventKey: String,
            event: RealtimeEvent,
            registrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
        ): RealtimeCanonicalFinalSinkEnvelope =
            RealtimeCanonicalFinalSinkEnvelope(
                grant = grant,
                partition =
                    DurablePartitionKey(
                        serverId = grant.serverId,
                        principalId = grant.principalId,
                        consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                        environmentId = grant.environmentId,
                    ),
                eventKey = eventKey,
                event = event,
                source = "red-test",
                capturedRegistrations = registrations,
            )

        fun durablePartition(grant: RealtimeGrantRecord): DurablePartitionKey =
            DurablePartitionKey(
                serverId = grant.serverId,
                principalId = grant.principalId,
                consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                environmentId = grant.environmentId,
            )

        fun messageEvent(body: String, seq: Long): MessageNew =
            MessageNew(
                Message(
                    id = MessageId("message-$body-$seq"),
                    threadId = ThreadId("channel-a"),
                    channelId = ChannelId("channel-a"),
                    seq = seq,
                    author = MemberProfile(MemberId("member-a"), "Member A", "member-a", MemberKind.Human),
                    timestampLabel = "now",
                    body = body,
                )
            )
    }
}
