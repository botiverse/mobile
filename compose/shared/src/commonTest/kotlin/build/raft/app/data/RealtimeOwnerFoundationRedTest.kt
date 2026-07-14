package build.raft.app.data

/*
 * RealtimeOwnerFoundationRedTest — reviewer-authored acceptance REDs (matrix C1..C5)
 * for the rebuilt "realtime owner foundation".
 *
 * CONTRACT: these tests are written FIRST, against Zhao's FROZEN type contract:
 *
 *     internal data class RealtimeOwnerEpoch(serverId, memberId, connectionEpoch)
 *     internal data class RealtimeOwnerEnvelope<out T>(owner, payload)
 *
 * with connectionEpoch globally monotonic, minted ONLY by the central provider-level
 * authority; revoke burns (bumps) it and never reuses; every receiver-private
 * ApplyRealtime* intent carries exactly ONE non-null envelope; the final guard is
 * runIfCurrentOwner(owner: RealtimeOwnerEpoch, block) and stores never cache or
 * rebuild the current owner.
 *
 * This file shares ZERO code with the implementation. It is EXPECTED not to compile
 * against current main: the contract types and the envelope-shaped owner/store surfaces
 * do not exist yet. Mahua rebases these tests onto her implementation and fixes trivial
 * signature drift only — every place where the surface is a guess (not part of the
 * frozen contract) is tagged with a `// SIGNATURE-DRIFT:` comment. The SCENARIOS and
 * the asserted invariants are frozen and must not change during that rebase.
 *
 * DETERMINISM: every test is a deterministic schedule. Async hops are modeled with a
 * manual-queue dispatcher (explicitly drained) or an explicit closure queue; there are
 * no sleeps, wall-clock waits, or probabilistic races. kotlinx-coroutines-test is not
 * a project dependency, so tests use runBlocking (JVM/native targets) plus manual
 * queues, matching existing suite conventions.
 *
 * LEVELS: C1 and C4 exercise the REAL SharedHomeStore, and C5 the REAL SharedThreadStore
 * (their test fakes are manageable — see SharedHomeStoreTest / SharedThreadStoreTest).
 * C2 and C3 run at owner/recorded-sink level: the invariants live at the
 * authority/ingress boundary.
 *
 * PROVIDER DELEGATION: Provider→owner delegation (invalidation / release /
 * lookup-identity) is proven mechanically in
 * androidUnitTest/ProviderOwnerDelegationGateTest.kt against the real
 * SlockSharedStoreProvider (internal constructor). This file stays at owner/store level.
 *
 * DEDUP NOTE: C1/C2 (rejected event redelivered => applies) and C4 (applied batch
 * redelivered => no-op) pin redelivery BEHAVIOR only. They deliberately do NOT pin a
 * ledger protocol — an apply-committed ledger, an epoch-scoped accept-ledger cleared on
 * rotate/revoke, or any other design satisfies them equally.
 */

import build.raft.app.KuiklyThreadScreenPayload
import build.raft.app.SharedThreadDataSource
import build.raft.app.SharedThreadIntent
import build.raft.app.SharedThreadStore
import build.raft.app.data.realtime.PerReceiverStateScope
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeOwnerFoundationRedTest {

    // =====================================================================
    // C1 — enqueue-rotate-drain persistence-zero
    //
    // The historical hole this pins shut: the owner validated the token at
    // accept time, the sink boundary DROPPED it, and the store application
    // ran after a queue hop with no guard — mutating memory AND persistence
    // under an owner that had already rotated away.
    // =====================================================================

    // C1
    @Test
    fun c1ForegroundApplicationQueuedUnderEpochAThenRotatedAppliesAndPersistsNothing() = runBlocking {
        val authority = newAuthority()
        val epochA = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        val lane = ManualQueueDispatcher()
        val persistence = RecordingPreferenceStore()
        val versionStore = PerReceiverStateVersionStore(persistence)
        val storeScope = CoroutineScope(SupervisorJob() + lane)
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        activityNotificationPreference =
                                            ChannelActivityNotificationPreference(activityMuteSupported = true)
                                    )
                                )
                        )
                    ),
                scope = storeScope,
                perReceiverStateVersionStore = versionStore,
                // SIGNATURE-DRIFT: param name / guard type. Frozen bit: the store gets the
                // guard injected and calls runIfCurrentOwner(envelope.owner) AT APPLICATION
                // TIME (post queue hop); it never caches/rebuilds the current owner.
                realtimeOwnerMutationGuard = authority::runIfCurrentOwner
            )
        val owner = authority.owner // SIGNATURE-DRIFT: authority param name
        owner.registerSinks(
            RealtimeDomainEventSinks(
                // SIGNATURE-DRIFT: sink lambda shape — frozen bit is that the ENVELOPE (not a
                // bare serverId/event pair) crosses the sink boundary, so the queue hop below
                // cannot strip the owner. The lane is the store's own dispatch scope: nothing
                // runs until lane.drain().
                applyHomeShell = { envelope ->
                    store.dispatch(SharedHomeIntent.ApplyRealtimeEvent(envelope = envelope))
                }
            )
        )
        store.accept(SharedHomeIntent.Start)
        lane.drain()
        val writesBaseline = persistence.writes.size
        // Two probes: MessageNew mutates in-memory home state (preview + unread counts);
        // PerReceiverNotificationPrefsChanged writes the per-receiver version PERSISTENCE
        // when applied. Both must be zero after a stale drain.
        val staleMessage = MessageNew(message(id = "c1-m1", seq = 5, body = "queued under A"))
        val stalePrefs =
            PerReceiverNotificationPrefsChanged(
                serverId = ServerId("server-1"),
                scopeId = ChannelId("channel-1"),
                preference =
                    ChannelActivityNotificationPreference(
                        activityMuted = true,
                        muteFromSeq = 42,
                        activityMuteSupported = true
                    ),
                prefsVersion = 7
            )

        owner.accept(envelope(epochA, staleMessage)) // SIGNATURE-DRIFT: accept(envelope[, source])
        owner.accept(envelope(epochA, stalePrefs))
        assertTrue(
            lane.hasPending,
            "C1 precondition: accepted events must be QUEUED (sink boundary crossed) and not yet applied"
        )
        val epochB = authority.rotateOwner(serverId = "server-1", memberId = "user-1") // rotate to B before drain
        lane.drain()

        val stale = homeContent(store)
        assertEquals(
            0,
            stale.snapshot.unreadTotal,
            "C1 invariant: an application queued under epoch A must not mutate unread totals after rotation"
        )
        assertEquals(
            0,
            stale.snapshot.channels.single().unreadCount,
            "C1 invariant: per-channel unread count must be untouched by a stale-epoch drain"
        )
        assertNull(
            stale.snapshot.channels.single().lastMessagePreview,
            "C1 invariant: home snapshot (message preview) must be untouched by a stale-epoch drain"
        )
        assertFalse(
            stale.snapshot.channels.single().activityMuted,
            "C1 invariant: per-receiver prefs state must be untouched by a stale-epoch drain"
        )
        assertEquals(
            0L,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            ),
            "C1 invariant: the per-receiver version ledger must record nothing for a stale-epoch apply"
        )
        assertEquals(
            writesBaseline,
            persistence.writes.size,
            "C1 invariant: PERSISTENCE must be untouched by a stale-epoch drain (memory-only rejection is not enough)"
        )

        // The SAME events (identical identity) under B's epoch must apply normally.
        // Behavior pinned: a guard-REJECTED event was never applied, so redelivering it
        // under the current epoch MUST apply it. (How dedup achieves this — apply-committed
        // ledger, epoch-scoped ledger cleared on rotate, ... — is not pinned here.)
        owner.accept(envelope(epochB, staleMessage))
        owner.accept(envelope(epochB, stalePrefs))
        lane.drain()

        val live = homeContent(store)
        assertEquals(
            1,
            live.snapshot.unreadTotal,
            "C1 invariant: a rejected-then-redelivered event must apply under the CURRENT epoch (rejection is not application)"
        )
        assertEquals(
            "queued under A",
            live.snapshot.channels.single().lastMessagePreview,
            "C1 invariant: message content must land under the current epoch"
        )
        assertTrue(
            live.snapshot.channels.single().activityMuted,
            "C1 invariant: prefs fact must apply under the current epoch"
        )
        assertEquals(
            7L,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            ),
            "C1 invariant: version ledger commits exactly when the fact actually applies"
        )
        assertTrue(
            persistence.writes.size > writesBaseline,
            "C1 invariant: the current-epoch apply must reach persistence (proves the zero-write assertion above was not vacuous)"
        )
        storeScope.cancel()
    }

    // C1 (background-unread projection variant — the exact historical hole: the
    // background unread lane dropped the token at the sink boundary and, after the
    // queue hop, wrote memory AND persistence under a rotated-away owner).
    @Test
    fun c1BackgroundUnreadProjectionQueuedUnderEpochAThenRotatedAppliesAndPersistsNothing() = runBlocking {
        val authority = newAuthority()
        // Background unread events for server-2 arrive over server-2's own connection,
        // so the owner epoch under test is the one minted for THAT connection.
        val epochA = authority.rotateOwner(serverId = "server-2", memberId = "user-1")
        val lane = ManualQueueDispatcher()
        val persistence = RecordingPreferenceStore()
        val versionStore = PerReceiverStateVersionStore(persistence)
        val storeScope = CoroutineScope(SupervisorJob() + lane)
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            serverUnreadCounts = mapOf("server-1" to 0, "server-2" to 3)
                        )
                    ),
                scope = storeScope,
                perReceiverStateVersionStore = versionStore,
                realtimeOwnerMutationGuard = authority::runIfCurrentOwner // SIGNATURE-DRIFT: see C1 foreground test
            )
        val owner = authority.owner // SIGNATURE-DRIFT
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeUnreadProjection = { envelope ->
                    store.dispatch(SharedHomeIntent.ApplyBackgroundServerUnreadEvent(envelope = envelope))
                }
            )
        )
        store.accept(SharedHomeIntent.Start)
        lane.drain()
        val writesBaseline = persistence.writes.size
        val visibleBefore = homeContent(store).snapshot
        // Memory probe: background MessageNew for a non-visible server bumps
        // serverUnreadCounts. Persistence probe: an accepted background read-state
        // fact writes the read-state checkpoint through the version store.
        val staleBackgroundMessage =
            MessageNew(message(id = "c1-bg1", seq = 101, body = "background", channelId = "channel-b"))
        val staleBackgroundFact =
            PerReceiverReadStateChanged(
                serverId = ServerId("server-2"),
                scopes =
                    listOf(
                        PerReceiverReadStateScope(
                            scopeId = ChannelId("channel-b"),
                            maxReadSeq = 101,
                            readStateVersion = 5
                        )
                    )
            )

        owner.acceptHomeUnreadProjection(envelope(epochA, staleBackgroundMessage))
        owner.acceptHomeUnreadProjection(envelope(epochA, staleBackgroundFact))
        assertTrue(
            lane.hasPending,
            "C1-bg precondition: background projections must be queued behind the lane hop, not applied inline"
        )
        val epochB = authority.rotateOwner(serverId = "server-2", memberId = "user-1")
        lane.drain()

        assertEquals(
            3,
            store.state.value.serverUnreadCounts["server-2"],
            "C1-bg invariant: background unread count must be untouched by a stale-epoch drain"
        )
        assertEquals(
            visibleBefore,
            homeContent(store).snapshot,
            "C1-bg invariant: visible home snapshot must be untouched by a stale-epoch background drain"
        )
        assertEquals(
            0L,
            versionStore.readVersion(
                serverId = ServerId("server-2"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.ReadState,
                scopeId = ChannelId("channel-b")
            ),
            "C1-bg invariant: stale-epoch read-state fact must not reach the persisted checkpoint"
        )
        assertEquals(
            writesBaseline,
            persistence.writes.size,
            "C1-bg invariant: PERSISTENCE must record zero writes from a stale-epoch background drain"
        )

        // Same message identity under the new epoch applies normally (3 -> 4).
        // Count is asserted BEFORE the fact redelivery below, because the accepted
        // fact schedules a canonical reconcile whose reload would make later count
        // assertions timing-dependent.
        owner.acceptHomeUnreadProjection(envelope(epochB, staleBackgroundMessage))
        lane.drain()
        assertEquals(
            4,
            store.state.value.serverUnreadCounts["server-2"],
            "C1-bg invariant: a rejected-then-redelivered background event must apply under the current epoch"
        )

        // POSITIVE CONTROL for the persistence-zero assertions above: redeliver the SAME
        // read-state fact under epoch B and require it to actually REACH persistence.
        // Without this leg, a fact path that is not wired to persistence at all would
        // make the stale leg's zero-write assertion vacuously green. Timing-safe probes
        // only: the version ledger value and the raw write counter (deliberately no
        // unread-count assertion here — that depends on the reconcile reload).
        owner.acceptHomeUnreadProjection(envelope(epochB, staleBackgroundFact))
        lane.drain()
        assertEquals(
            5L,
            versionStore.readVersion(
                serverId = ServerId("server-2"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.ReadState,
                scopeId = ChannelId("channel-b")
            ),
            "C1-bg invariant: the read-state fact applied under the current epoch must commit to the version ledger"
        )
        assertTrue(
            persistence.writes.size > writesBaseline,
            "C1-bg invariant: the current-epoch fact must write persistence (proves the stale leg's zero-write assert was not vacuous)"
        )
        storeScope.cancel()
    }

    // =====================================================================
    // C2 — revoke null/stale zero (owner-level burn primitive, recorded sinks:
    // the invariant lives at the authority/ingress boundary).
    //
    // Provider→owner delegation (invalidation / release / lookup-identity) is
    // proven mechanically in androidUnitTest/ProviderOwnerDelegationGateTest.kt
    // against the real SlockSharedStoreProvider (internal constructor). This
    // file stays at owner/store level.
    // =====================================================================

    // C2
    @Test
    fun c2RevokeBurnsEpochLateStaleEventIsInertAndReconnectMintsStrictlyHigher() {
        val authority = newAuthority()
        val epochA = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        val owner = authority.owner // SIGNATURE-DRIFT
        val delivered = mutableListOf<RealtimeOwnerEnvelope<RealtimeEvent>>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> delivered += envelope }
            )
        )
        owner.accept(envelope(epochA, MessageNew(message(id = "c2-pre", seq = 1))))
        assertEquals(1, delivered.size, "C2 precondition: a live-epoch event must fan out before revoke")

        authority.revokeOwner(serverId = "server-1", memberId = "user-1") // logout burns the epoch

        // (a) A late event still carrying the pre-revoke epoch mutates nothing.
        // Distinct identity on purpose: dedup must not be what saves us here —
        // ONLY the burned epoch may drop this event.
        val lateEvent = MessageNew(message(id = "c2-late", seq = 2, body = "late after revoke"))
        owner.accept(envelope(epochA, lateEvent))
        assertEquals(
            1,
            delivered.size,
            "C2 invariant: a pre-revoke epoch must be dead at ingress — the late event may not reach any sink"
        )
        var staleGuardRan = false
        authority.runIfCurrentOwner(epochA) { staleGuardRan = true }
        assertFalse(
            staleGuardRan,
            "C2 invariant: runIfCurrentOwner must never run a block for a burned epoch"
        )

        // (b) No API path applies anything without an envelope. This is a TYPE-LEVEL
        // guarantee of the frozen contract (accept takes one non-null
        // RealtimeOwnerEnvelope<RealtimeEvent>; ApplyRealtime* intents take one
        // non-null envelope field; runIfCurrentOwner takes a non-null owner).
        // There is deliberately no runtime probe here — an unrepresentable state
        // cannot be exercised. Review gate instead: if the rebased implementation
        // still compiles with an `accept(serverId, event)`-shaped overload or a
        // nullable-owner guard anywhere on these surfaces, reject the PR.

        // (c) Reconnect mints a strictly higher connectionEpoch — burned values
        // are never reused.
        val epochB = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        assertTrue(
            epochB.connectionEpoch > epochA.connectionEpoch,
            "C2 invariant: reconnect must mint a STRICTLY higher connectionEpoch " +
                "(was ${epochA.connectionEpoch}, got ${epochB.connectionEpoch}); revoke burns, never reuses"
        )

        // (d) The SAME event (identical identity as the rejected late delivery)
        // applies under the new epoch — an ingress-rejected event was never applied,
        // so its redelivery must not be treated as a duplicate. (Behavior only; the
        // dedup mechanism is not pinned.)
        owner.accept(envelope(epochB, lateEvent))
        assertEquals(
            2,
            delivered.size,
            "C2 invariant: the same event under the post-reconnect epoch must apply (rejection is not application)"
        )
        assertEquals(
            epochB,
            delivered.last().owner,
            "C2 invariant: the fan-out envelope must carry the epoch it was accepted under"
        )
    }

    // =====================================================================
    // C3 — single-authority cross-principal burn semantics (owner level)
    //
    // Production baseline: the provider LOOKUP returns ONE single owner
    // instance for all scopes (no per-shell hubs), so the old two-hub framing
    // collapses. Single-authority INSTANCE IDENTITY across the provider entries
    // is T3's job in androidUnitTest/ProviderOwnerDelegationGateTest.kt; this
    // test pins the SEMANTICS of that single authority — an account switch
    // (cross-principal rotation) burns the ENTIRE previous principal, across
    // all servers. Direct owner/authority construction is fine HERE for exactly
    // that reason.
    // =====================================================================

    // C3
    @Test
    fun c3CrossPrincipalRotationBurnsAllOfPreviousPrincipalsGrantsAcrossServers() {
        // The original twin-authority blocker is CROSS-PRINCIPAL: an account switch
        // to (server-1, user-2) while user-1 grants are still live. ONE central
        // authority means the switch burns the ENTIRE previous principal — including
        // user-1's grant on ANOTHER server (the cross-server leg). A keyed authority
        // that keeps user-1's grants alive per-key is exactly the blocker and must
        // fail here; per-server keying for the SAME principal (asserted as
        // coexistence below) is compatible.
        val authority = newAuthority()
        val owner = authority.owner // SIGNATURE-DRIFT
        val delivered = mutableListOf<RealtimeOwnerEnvelope<RealtimeEvent>>()
        val applied = mutableListOf<RealtimeEvent>()
        // Explicit closure queue = the store-application hop, drained by hand.
        val applyQueue = ArrayDeque<() -> Unit>()
        fun drainApplications() {
            while (applyQueue.isNotEmpty()) applyQueue.removeFirst().invoke()
        }
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope ->
                    delivered += envelope
                    applyQueue += {
                        authority.runIfCurrentOwner(envelope.owner) { applied += envelope.payload }
                    }
                }
            )
        )

        // The user-1 context holds grants on TWO servers (a multi-server process is
        // legitimate, so same-principal grants on different servers must coexist).
        val epochA1 = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        val epochA2 = authority.rotateOwner(serverId = "server-2", memberId = "user-1")
        owner.accept(envelope(epochA1, MessageNew(message(id = "c3-a1", seq = 1))))
        owner.accept(envelope(epochA2, MessageNew(message(id = "c3-a2", seq = 1, channelId = "channel-b"))))
        drainApplications()
        assertEquals(
            2,
            applied.size,
            "C3 precondition: same-principal grants on different servers coexist and both apply"
        )

        owner.accept(envelope(epochA1, MessageNew(message(id = "c3-a3", seq = 2)))) // queued, NOT drained
        // Account switch: cross-principal rotation on server-1.
        val epochB = authority.rotateOwner(serverId = "server-1", memberId = "user-2")
        drainApplications()

        assertEquals(
            2,
            applied.size,
            "C3 invariant: an application queued under the previous principal before the account switch must drain to ZERO applies"
        )
        owner.accept(envelope(epochA1, MessageNew(message(id = "c3-a4", seq = 3))))
        assertEquals(
            3,
            delivered.size,
            "C3 invariant: ingress under the switched-away principal's grant must fan out nothing new"
        )
        // Cross-server leg: the account switch burns user-1 EVERYWHERE, not only on
        // the server that rotated — a lingering server-2/user-1 grant is the blocker.
        owner.accept(envelope(epochA2, MessageNew(message(id = "c3-a5", seq = 2, channelId = "channel-b"))))
        assertEquals(
            3,
            delivered.size,
            "C3 invariant: the previous principal's grant on ANOTHER server is burned by the account switch too"
        )
        var staleGuardRan = false
        authority.runIfCurrentOwner(epochA1) { staleGuardRan = true }
        authority.runIfCurrentOwner(epochA2) { staleGuardRan = true }
        assertFalse(
            staleGuardRan,
            "C3 invariant: runIfCurrentOwner must refuse BOTH of the previous principal's grants after the switch"
        )

        // The user-2 epoch delivers and applies normally.
        owner.accept(envelope(epochB, MessageNew(message(id = "c3-b1", seq = 4))))
        drainApplications()
        assertEquals(
            4,
            delivered.size,
            "C3 invariant: ingress under the new principal's grant must deliver normally"
        )
        assertEquals(
            3,
            applied.size,
            "C3 invariant: the new principal's queued application must apply under its grant"
        )
        assertEquals(
            epochB,
            delivered.last().owner,
            "C3 invariant: the new principal's fan-out envelope carries the epoch it was accepted under"
        )
    }

    // =====================================================================
    // C4 — repair-batch no-loss
    // =====================================================================

    // C4
    @Test
    fun c4SyncResumeRepairBatchOfEightLandsExactlyOnceInOrderAndRedeliveryAppliesNothing() = runBlocking {
        val authority = newAuthority()
        val epoch = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        val lane = ManualQueueDispatcher()
        val storeScope = CoroutineScope(SupervisorJob() + lane)
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1"))),
                scope = storeScope,
                realtimeOwnerMutationGuard = authority::runIfCurrentOwner // SIGNATURE-DRIFT: see C1
            )
        val fanOutOrder = mutableListOf<String>()
        val owner = authority.owner // SIGNATURE-DRIFT
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope ->
                    // Record contained message ids so the order assertion holds whether
                    // the rebuilt owner fans out the resume batch as ONE event or as
                    // eight expanded per-message events.
                    fanOutOrder += envelope.payload.containedMessageIds()
                    store.dispatch(SharedHomeIntent.ApplyRealtimeEvent(envelope = envelope))
                }
            )
        )
        store.accept(SharedHomeIntent.Start)
        lane.drain()

        // ONE real SyncResumeResponse repair batch carrying 8 messages, accepted as a
        // single envelope — the exact ingress shape the owner's fold/expansion path
        // (acceptedEventsForFold in the pre-revert owner) sees after a seq-gap repair.
        // This is the hole a pre-expanded 8x MessageNew loop can NEVER hit: an
        // expansion that drops appliedEvents, or a per-event job that cancels its
        // predecessor, loses messages INSIDE one accepted batch.
        val repairBatch =
            SyncResumeResponse(
                messages = (1..8).map { n -> message(id = "c4-m$n", seq = n.toLong(), body = "repair-$n") },
                currentSeq = 8,
                hasMore = false
            )
        owner.accept(envelope(epoch, repairBatch))
        assertTrue(lane.hasPending, "C4 precondition: the batch application is queued before the drain")
        lane.drain()

        assertEquals(
            (1..8).map { "c4-m$it" },
            fanOutOrder,
            "C4 invariant: all 8 repair messages must survive the owner's batch expansion, in seq order, exactly once"
        )
        val afterBatch = homeContent(store)
        assertEquals(
            8,
            afterBatch.snapshot.channels.single().unreadCount,
            "C4 invariant: all 8 messages of the batch must land in store state exactly once (no in-batch loss or double count)"
        )
        assertEquals(
            8,
            afterBatch.snapshot.unreadTotal,
            "C4 invariant: unread total agrees — no message was dropped by expansion or cancelled by a successor's job"
        )
        assertEquals(
            "repair-8",
            afterBatch.snapshot.channels.single().lastMessagePreview,
            "C4 invariant: the LAST message of the batch is the final preview — order preserved through the lane"
        )

        // Redelivering the IDENTICAL SyncResumeResponse applies zero additional
        // changes. Deliberate positive/negative pair with C1: identical redelivery
        // arithmetic, opposite outcomes — applied-then-redelivered is a no-op,
        // rejected-then-redelivered (C1) applies. Behavior only; the dedup mechanism
        // (per-message ledger, batch checksum, epoch-scoped ledger, ...) is not pinned.
        owner.accept(envelope(epoch, repairBatch))
        lane.drain()
        assertEquals(
            8,
            fanOutOrder.size,
            "C4 invariant: a redelivered already-applied repair batch must not fan out any message again"
        )
        val afterReplay = homeContent(store)
        assertEquals(
            8,
            afterReplay.snapshot.channels.single().unreadCount,
            "C4 invariant: redelivery of an applied batch must change nothing (no double count)"
        )
        storeScope.cancel()
    }

    // =====================================================================
    // C5 — reject-recoverable (no fail-stuck), on the REAL SharedThreadStore
    //
    // Target: SharedThreadStore gap recovery. newerMessagesStatus carries a
    // self-suppression guard (`if (initial.newerMessagesStatus == "loading") return`,
    // see recoverRealtimeGapFromVisibleTail) layered over RealtimeCursorPolicy's
    // loading suppression. If rotation rejects the in-flight completion SILENTLY,
    // status stays "loading" forever and every later trigger self-suppresses:
    // fail-STUCK, not fail-closed.
    //
    // COMPILE gate kept from the frozen guard: runIfCurrentOwner's result must
    // report whether the block ran (rejection observable to the caller) — the
    // store's wiring can only write a recoverable terminal status if the guard
    // tells it the completion was refused. A Unit-returning silent-skip guard
    // makes that wiring impossible, and this test's park-rotate-release schedule
    // will catch it as a stranded "loading".
    // =====================================================================

    // C5
    @Test
    fun c5RotationRejectedGapRecoveryLeavesThreadRecoverableNotStuckLoading() = runBlocking {
        val authority = newAuthority()
        val epochA = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        val threadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            ParkableThreadApi(
                bodyText = threadMessagesResponse("current body", id = "m10", seq = 10),
                newerBodyText = threadSyncResponse("stale gap body", id = "m11", seq = 11)
            )
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = api,
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                    ),
                scope = threadScope,
                realtimeOwnerMutationGuard = authority::runIfCurrentOwner // SIGNATURE-DRIFT: injected guard; see C1
            )
        // Start is a local HTTP-load path: per the frozen contract it is a DIFFERENT
        // named intent with no envelope (it must not forge one).
        store.accept(SharedThreadIntent.Start(threadPayload().copy(canLoadMoreAfter = false)))
        assertEquals(
            listOf("current body"),
            store.state.value.payload.messages.map { it.body },
            "C5 precondition: thread loaded its visible tail (m10/seq 10)"
        )

        // Gap recovery starts under epoch A: a realtime m12/seq 12 above the visible
        // tail opens a gap; the sync fetch PARKS on the deferred (deterministic park,
        // no wall clock).
        val gapJob = launch {
            store.accept(
                // SIGNATURE-DRIFT: envelope-shaped receiver-private intent; today this
                // is ApplyRealtimeEvent(event, activeReplyParentMessageId).
                SharedThreadIntent.ApplyRealtimeEvent(
                    envelope = envelope(epochA, MessageNew(message(id = "m12", seq = 12, body = "opens the gap")))
                )
            )
        }
        api.syncStarted!!.await()
        assertEquals(
            "loading",
            store.state.value.payload.newerMessagesStatus,
            "C5 precondition: gap recovery is in flight under epoch A"
        )

        // Rotation to B rejects the in-flight completion; then release the parked fetch.
        val epochB = authority.rotateOwner(serverId = "server-1", memberId = "user-1")
        api.releaseSync!!.complete(Unit)
        gapJob.join()

        val rejected = store.state.value.payload
        assertEquals(
            listOf("current body"),
            rejected.messages.map { it.body },
            "C5 invariant: the stale completion's payload must never land after rotation"
        )
        assertNotEquals(
            "loading",
            rejected.newerMessagesStatus,
            "C5 invariant: a rejected completion must write a RECOVERABLE terminal status — a stranded 'loading' is fail-stuck " +
                "(the store's own loading self-suppression would then swallow every later trigger)"
        )

        // A legitimate trigger under epoch B CAN start AND complete recovery: neither
        // the store's loading-suppression nor any latch in the authority survived the
        // rejection.
        api.newerBodyText = threadSyncResponse("recovered under B", id = "m13", seq = 13)
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                envelope = envelope(epochB, MessageNew(message(id = "m13", seq = 13, body = "second trigger")))
            )
        )

        assertEquals(
            listOf(10L, 10L),
            api.syncSinceSeqs,
            "C5 invariant: recovery genuinely restarted under epoch B (second sync fetch issued, not self-suppressed)"
        )
        val recovered = store.state.value.payload
        assertEquals(
            listOf("current body", "recovered under B"),
            recovered.messages.map { it.body },
            "C5 invariant: recovery under the current epoch completes normally"
        )
        assertEquals(
            "idle",
            recovered.newerMessagesStatus,
            "C5 invariant: the thread returns to idle after the current-epoch recovery"
        )
        threadScope.cancel()
    }

    // =====================================================================
    // Helpers (test-local only; zero code shared with the implementation)
    // =====================================================================

    /**
     * Minimal SharedThreadNetworkApi for the C5 gap-recovery schedule. Only
     * channelMessages/messageSync carry behavior; messageSync can PARK on a
     * CompletableDeferred pair (same idiom as SharedThreadStoreTest.FakeThreadApi's
     * syncStarted/releaseSync) so the test controls exactly when the in-flight
     * completion lands relative to the rotation. Everything else is an inert 200.
     */
    private class ParkableThreadApi(
        var bodyText: String,
        var newerBodyText: String
    ) : SharedThreadNetworkApi {
        val syncSinceSeqs = mutableListOf<Long>()
        var syncStarted: CompletableDeferred<Unit>? = null
        var releaseSync: CompletableDeferred<Unit>? = null

        override suspend fun channelMessages(
            threadId: String,
            limit: Int,
            beforeSeq: Long?,
            afterSeq: Long?
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = if (afterSeq != null) newerBodyText else bodyText)

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse {
            syncSinceSeqs += sinceSeq
            syncStarted?.complete(Unit)
            releaseSync?.await()
            return HomeNetworkResponse(code = 200, bodyText = newerBodyText)
        }

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>,
            randomId: String?
        ): HomeNetworkResponse = inert()

        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse = inert()

        override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse = inert()

        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse = inert()

        override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse = inert()

        override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse = inert()

        override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse = inert()

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse = inert()

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse = inert()

        override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse = inert()

        override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse = inert()

        override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse = inert()

        override suspend fun convertMessageToTask(messageId: String): HomeNetworkResponse = inert()

        override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse = inert()

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse = inert()

        private fun inert(): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
    }

    /**
     * Deterministic queue hop: every coroutine dispatched to this dispatcher is
     * parked until [drain] runs it on the test thread. No wall-clock anywhere.
     */
    private class ManualQueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private val lock = SynchronizedObject()

        val hasPending: Boolean
            get() = synchronized(lock) { queue.isNotEmpty() }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) { queue.addLast(block) }
        }

        fun drain() {
            // Keep going until quiescent: a drained runnable may enqueue follow-ups.
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    /** Persistence probe: records every write while behaving like the in-memory store. */
    private class RecordingPreferenceStore : SharedPreferenceStore {
        private val delegate = InMemorySlockKeyValueStore()
        val writes = mutableListOf<Pair<String, String?>>()

        override fun getString(key: String): String? = delegate.getString(key)

        override fun putString(key: String, value: String?) {
            writes += key to value
            delegate.putString(key, value)
        }
    }

    private class StubHomeApi : SlockHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "{}")

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "[]")
    }

    // SIGNATURE-DRIFT: authority type name and mint/burn method names. The surface
    // below is the simplest one consistent with the frozen contract and the shape of
    // SharedRealtimeDomainEventOwner:
    //   rotateOwner(serverId, memberId): RealtimeOwnerEpoch   — initial grant AND takeover both MINT
    //   revokeOwner(serverId, memberId)                       — burns; no successor until reconnect mints
    //   runIfCurrentOwner(owner, block): Boolean              — frozen guard; Boolean result is the
    //                                                           observable-rejection requirement (C5)
    // connectionEpoch is globally monotonic across ALL mints of one authority instance.
    private fun newAuthority(): TestRealtimeOwnerAuthority = TestRealtimeOwnerAuthority()

    private class TestRealtimeOwnerAuthority {
        val owner = testRealtimeDomainEventOwner()

        fun rotateOwner(serverId: String, memberId: String): RealtimeOwnerEpoch =
            owner.rotateOwner(serverId, memberId)

        fun revokeOwner(serverId: String, memberId: String) {
            owner.revokeOwner()
        }

        fun runIfCurrentOwner(ownerEpoch: RealtimeOwnerEpoch, block: () -> Unit): Boolean =
            owner.runIfCurrentOwner(ownerEpoch, block)
    }

    // Provider→owner delegation (invalidation / release / lookup-identity) is proven
    // mechanically in androidUnitTest/ProviderOwnerDelegationGateTest.kt against the
    // real SlockSharedStoreProvider (internal constructor). This file stays at
    // owner/store level — no self-constructed provider/foundation surface here.

    /** Message ids contained in a fan-out payload, whether batched or pre-expanded. */
    private fun RealtimeEvent.containedMessageIds(): List<String> =
        when (this) {
            is MessageNew -> listOf(message.id.value)
            is SyncResumeResponse -> messages.map { it.id.value }
            else -> emptyList()
        }

    // The envelope's owner epoch is the ONLY authority datum that travels with a
    // payload. There is deliberately no parallel serverId/event/ownerToken argument
    // anywhere in this file: consumers derive the server from the owner epoch (or
    // the event itself), never from a forgeable side channel.
    private fun envelope(
        owner: RealtimeOwnerEpoch,
        event: RealtimeEvent
    ): RealtimeOwnerEnvelope<RealtimeEvent> =
        RealtimeOwnerEnvelope(owner = owner, payload = event)

    private fun homeContent(store: SharedHomeStore): SharedHomeContentState.Content {
        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content, "expected loaded home content, was $content")
        return content
    }

    private fun sourceWithSnapshot(snapshot: HomeSnapshot): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            api = StubHomeApi(),
            parseHome = { _, _ -> DataResult.Content(snapshot) }
        )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock"),
                    StoredAuthServer(id = "server-2", name = "Work", slug = "work")
                )
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock Community"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human
                ),
            channels = listOf(channel("channel-1", "general")),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun channel(id: String, name: String): HomeChannelRow =
        HomeChannelRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = name
        )

    private fun message(
        id: String,
        seq: Long,
        body: String = "hello",
        channelId: String = "channel-1"
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            seq = seq,
            // Author is never the receiving member (user-1): unread projections skip
            // the receiver's own messages, and these tests count unread as the probe.
            author = MemberProfile(MemberId("member-2"), "Cindy", "cindy", MemberKind.Human),
            timestampLabel = "now",
            body = body
        )

    private fun threadPayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true
        )

    private fun threadMessagesResponse(body: String, id: String, seq: Long): String =
        """
        {
          "historyLimited": false,
          "messages": [
            {
              "id": "$id",
              "seq": $seq,
              "channelId": "channel-1",
              "content": "$body",
              "createdAt": "2026-05-19T16:00:00Z",
              "senderId": "u1",
              "senderName": "Artin",
              "senderType": "user",
              "messageType": "chat"
            }
          ]
        }
        """.trimIndent()

    private fun threadSyncResponse(body: String, id: String, seq: Long): String =
        """
        [
          {
            "id": "$id",
            "seq": $seq,
            "channelId": "channel-1",
            "content": "$body",
            "createdAt": "2026-05-19T16:00:00Z",
            "senderId": "u1",
            "senderName": "Artin",
            "senderType": "user",
            "messageType": "chat"
          }
        ]
        """.trimIndent()
}
