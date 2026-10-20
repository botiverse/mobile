package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAvatarHttpLoadCoordinatorTest {
    @Test
    fun genericIconIsAFailureAffordanceNotALoadingPlaceholder() {
        // task #343: cold start must go blank -> real avatar, never default icon -> real avatar.
        assertFalse(raftAvatarHttpShowsFallback(RaftAvatarHttpLoadSnapshot()))
        assertFalse(
            raftAvatarHttpShowsFallback(
                RaftAvatarHttpLoadSnapshot(
                    cache = RaftAvatarHttpCacheState.InFlight,
                    painter = RaftAvatarHttpPainterPhase.Loading
                )
            )
        )
        assertFalse(
            raftAvatarHttpShowsFallback(
                RaftAvatarHttpLoadSnapshot(
                    cache = RaftAvatarHttpCacheState.Ready,
                    painter = RaftAvatarHttpPainterPhase.Success
                )
            )
        )
        assertTrue(
            raftAvatarHttpShowsFallback(RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Cooldown))
        )
        assertTrue(
            raftAvatarHttpShowsFallback(
                RaftAvatarHttpLoadSnapshot(
                    cache = RaftAvatarHttpCacheState.Ready,
                    painter = RaftAvatarHttpPainterPhase.Error
                )
            )
        )
    }

    @Test
    fun sameUploadedAvatarRowsJoinOneActiveRequestAndReuseReadyCache() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()

        val firstRowLease = assertNotNull(coordinator.tryAcquire(key))
        assertNull(coordinator.tryAcquire(key), "second row must join the active request")
        assertEquals(
            "single_flight_join",
            coordinator.stateFlow(key).value.diagnosticsCacheState(localOwnerToken = null)
        )

        coordinator.reportPainterPhase(key, firstRowLease, RaftAvatarHttpPainterPhase.Loading)
        coordinator.reportPainterPhase(key, firstRowLease, RaftAvatarHttpPainterPhase.Success)

        val ready = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Ready, ready.cache)
        assertEquals(RaftAvatarHttpPainterPhase.Success, ready.painter)
        assertNull(coordinator.tryAcquire(key), "ready rows must reuse Kuikly's shared image cache")
    }

    @Test
    fun ordinaryUrlIsWithheldOnlyWhileIdleOrCoolingDown() {
        val url = "https://cdn.example/avatar.webp"
        val malformedManagedModel = "slock-avatar-render-v1:not-base64"
        val idle = RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Idle)
        val joined =
            RaftAvatarHttpLoadSnapshot(
                cache = RaftAvatarHttpCacheState.InFlight,
                generation = 1L,
                ownerToken = 7L
            )
        val ready = RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Ready)
        val coolingDown = RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Cooldown)

        assertNull(raftAvatarHttpAdmittedImageModel(url, idle, ownsCurrentRequest = false))
        assertNull(raftAvatarHttpAdmittedImageModel(url, coolingDown, ownsCurrentRequest = false))
        // A row that joined someone else's flight renders it too, instead of waiting for the owner
        // to be drawn (HarmonyOS 1.13.0: every server avatar stayed black).
        assertEquals(url, raftAvatarHttpAdmittedImageModel(url, joined, ownsCurrentRequest = false))
        // The durable refresh still downloads once: only the owner (or a ready generation) may.
        assertFalse(raftAvatarHttpLoadIsAdmitted(joined, ownsCurrentRequest = false))
        assertNull(
            raftAvatarHttpAdmittedImageModel(
                malformedManagedModel,
                idle,
                ownsCurrentRequest = false
            )
        )
        assertEquals(url, raftAvatarHttpAdmittedImageModel(url, joined, ownsCurrentRequest = true))
        assertEquals(url, raftAvatarHttpAdmittedImageModel(url, ready, ownsCurrentRequest = false))
    }

    @Test
    fun dnsFailureFallsBackThenCooldownAllowsSameUrlToRecover() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val firstLease = assertNotNull(coordinator.tryAcquire(key))

        coordinator.reportPainterPhase(key, firstLease, RaftAvatarHttpPainterPhase.Error)
        val apiAttempt = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.InFlight, apiAttempt.cache)
        assertEquals(RaftAvatarHttpEndpoint.Api, apiAttempt.endpoint)
        assertEquals(firstLease.ownerToken, apiAttempt.ownerToken)
        assertNull(coordinator.tryAcquire(key), "fallback remains the same single-flight owner")

        coordinator.reportPainterPhase(key, firstLease, RaftAvatarHttpPainterPhase.Error)
        val coolingDown = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Cooldown, coolingDown.cache)
        assertEquals(500L, coordinator.retryDelayMillis(coolingDown))
        assertNull(coordinator.tryAcquire(key))

        nowMillis += 500L
        val recoveryLease = assertNotNull(coordinator.tryAcquire(key))
        assertEquals(firstLease.generation + 1L, recoveryLease.generation)
        assertEquals(RaftAvatarHttpEndpoint.Cdn, coordinator.stateFlow(key).value.endpoint)
        coordinator.reportPainterPhase(key, recoveryLease, RaftAvatarHttpPainterPhase.Success)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun lateRowLocalFailureCannotOverrideNewerOwnerOrSuccessfulGeneration() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Success)

        coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Error)

        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
        assertEquals(RaftAvatarHttpPainterPhase.Success, coordinator.stateFlow(key).value.painter)
    }

    @Test
    fun ownerDisposalInvalidatesOnlyItsInFlightGeneration() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()
        val abandoned = assertNotNull(coordinator.tryAcquire(key))
        assertNull(coordinator.tryAcquire(key), "a mounted sibling joins rather than starting a duplicate")

        coordinator.releaseOwner(key, abandoned)
        val replacement = assertNotNull(coordinator.tryAcquire(key))
        assertEquals(abandoned.generation + 1L, replacement.generation)

        coordinator.reportPainterPhase(key, abandoned, RaftAvatarHttpPainterPhase.Success)
        assertEquals(replacement.ownerToken, coordinator.stateFlow(key).value.ownerToken)
        assertEquals(RaftAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun ownerDisposalWithRemainingObserversOrphansInsteadOfResetting() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()
        // Two rows observing the same avatar.
        coordinator.retain(key)
        coordinator.retain(key)

        val abandoned = assertNotNull(coordinator.tryAcquire(key))
        coordinator.releaseOwner(key, abandoned)

        // The entry stays InFlight with no owner so a surviving row can adopt the same generation
        // instead of restarting the download from scratch.
        val orphaned = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.InFlight, orphaned.cache)
        assertNull(orphaned.ownerToken)

        val adopter = assertNotNull(coordinator.tryAcquire(key))
        assertEquals(abandoned.generation, adopter.generation)
        assertEquals(adopter.ownerToken, coordinator.stateFlow(key).value.ownerToken)

        coordinator.release(key)
        coordinator.release(key)
    }

    @Test
    fun canonicalSourceChangeCreatesAnIndependentGenerationAndFencesOldCompletion() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val oldKey = uploadedAvatarKey()
        val newKey =
            oldKey.copy(
                primarySource = oldKey.primarySource.replace("deadbeef", "cafebabe"),
                apiFallbackSource = oldKey.apiFallbackSource?.replace("deadbeef", "cafebabe")
            )
        val oldLease = assertNotNull(coordinator.tryAcquire(oldKey))
        coordinator.releaseOwner(oldKey, oldLease)
        val newLease = assertNotNull(coordinator.tryAcquire(newKey))

        coordinator.reportPainterPhase(oldKey, oldLease, RaftAvatarHttpPainterPhase.Success)

        assertEquals(RaftAvatarHttpCacheState.Idle, coordinator.stateFlow(oldKey).value.cache)
        assertEquals(newLease.ownerToken, coordinator.stateFlow(newKey).value.ownerToken)
        assertEquals(RaftAvatarHttpCacheState.InFlight, coordinator.stateFlow(newKey).value.cache)
    }

    @Test
    fun terminalEntriesAreBoundedAfterRowsDetach() {
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                maxEntries = 2,
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        repeat(3) { index ->
            val key =
                uploadedAvatarKey().copy(
                    primarySource = uploadedAvatarKey().primarySource.replace("deadbeef", "avatar$index"),
                    apiFallbackSource = uploadedAvatarKey().apiFallbackSource?.replace("deadbeef", "avatar$index")
                )
            coordinator.retain(key)
            val lease = assertNotNull(coordinator.tryAcquire(key))
            coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Success)
            coordinator.release(key)
        }

        assertEquals(2, coordinator.entryCount())
    }

    @Test
    fun rowWaitingOnAnotherOwnerComesBackAfterTheStuckTimeout() {
        val owned = RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.InFlight, ownerToken = 7L)
        // HarmonyOS 1.13.0: the visible server avatar waited on a silent owner forever because
        // only Idle/Cooldown/orphaned flights ever asked for a lease.
        assertEquals(
            RaftAvatarHttpAcquireAttempt.AfterStuckOwnerTimeout,
            raftAvatarHttpAcquireAttempt(owned, ownsCurrentRequest = false)
        )
        assertEquals(
            RaftAvatarHttpAcquireAttempt.None,
            raftAvatarHttpAcquireAttempt(owned, ownsCurrentRequest = true)
        )
        assertEquals(
            RaftAvatarHttpAcquireAttempt.Now,
            raftAvatarHttpAcquireAttempt(owned.copy(ownerToken = null), ownsCurrentRequest = false)
        )
        assertEquals(
            RaftAvatarHttpAcquireAttempt.Now,
            raftAvatarHttpAcquireAttempt(RaftAvatarHttpLoadSnapshot(), ownsCurrentRequest = false)
        )
        assertEquals(
            RaftAvatarHttpAcquireAttempt.Now,
            raftAvatarHttpAcquireAttempt(
                RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Cooldown),
                ownsCurrentRequest = false
            )
        )
        assertEquals(
            RaftAvatarHttpAcquireAttempt.None,
            raftAvatarHttpAcquireAttempt(
                RaftAvatarHttpLoadSnapshot(cache = RaftAvatarHttpCacheState.Ready),
                ownsCurrentRequest = false
            )
        )
    }

    @Test
    fun recheckDelayLandsPastTheStuckTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(stuckOwnerTimeoutMillis = 100L, nowMillis = { nowMillis })
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        nowMillis += coordinator.stuckOwnerRecheckDelayMillis
        assertNotNull(coordinator.tryAcquire(key), "one recheck delay later a silent owner is adoptable")
    }

    @Test
    fun observerSuccessSettlesTheFlightForEveryRow() {
        val coordinator = RaftAvatarHttpLoadCoordinator()
        val key = uploadedAvatarKey()
        val ownerLease = assertNotNull(coordinator.tryAcquire(key))
        val flight = coordinator.stateFlow(key).value

        // The owner is composed but never drawn, so it never reports. A drawn observer does.
        coordinator.reportObserverPhase(key, flight.generation, flight.endpoint, RaftAvatarHttpPainterPhase.Loading)
        assertEquals(RaftAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)
        coordinator.reportObserverPhase(key, flight.generation, flight.endpoint, RaftAvatarHttpPainterPhase.Success)

        val settled = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Ready, settled.cache)
        assertNull(settled.ownerToken)
        // A late report from the original owner no longer changes the settled generation.
        coordinator.reportPainterPhase(key, ownerLease, RaftAvatarHttpPainterPhase.Error)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun observerErrorFallsBackToApiThenCoolsDown() {
        var nowMillis = 1_000L
        val coordinator = RaftAvatarHttpLoadCoordinator(nowMillis = { nowMillis })
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        val cdn = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpEndpoint.Cdn, cdn.endpoint)

        coordinator.reportObserverPhase(key, cdn.generation, cdn.endpoint, RaftAvatarHttpPainterPhase.Error)
        val api = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpEndpoint.Api, api.endpoint)
        assertEquals(RaftAvatarHttpCacheState.InFlight, api.cache)

        coordinator.reportObserverPhase(key, api.generation, api.endpoint, RaftAvatarHttpPainterPhase.Error)
        val cooled = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Cooldown, cooled.cache)
        assertTrue(raftAvatarHttpShowsFallback(cooled))
    }

    @Test
    fun lateOwnerResultForTheOldEndpointIsIgnoredAfterObserverFallback() {
        var nowMillis = 1_000L
        val coordinator = RaftAvatarHttpLoadCoordinator(nowMillis = { nowMillis })
        val key = uploadedAvatarKey()
        val owner = assertNotNull(coordinator.tryAcquire(key))
        val cdn = coordinator.stateFlow(key).value
        // An observer's CDN load fails first and moves the flight to the API endpoint.
        coordinator.reportObserverPhase(key, cdn.generation, RaftAvatarHttpEndpoint.Cdn, RaftAvatarHttpPainterPhase.Error)
        val api = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpEndpoint.Api, api.endpoint)

        // The owner's late CDN results must neither settle nor cool down the API attempt.
        coordinator.reportPainterPhase(key, owner, RaftAvatarHttpPainterPhase.Success, RaftAvatarHttpEndpoint.Cdn)
        assertEquals(api, coordinator.stateFlow(key).value)
        coordinator.reportPainterPhase(key, owner, RaftAvatarHttpPainterPhase.Error, RaftAvatarHttpEndpoint.Cdn)
        assertEquals(api, coordinator.stateFlow(key).value)

        // A result for the API URL it now renders still settles it.
        coordinator.reportPainterPhase(key, owner, RaftAvatarHttpPainterPhase.Success, RaftAvatarHttpEndpoint.Api)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun lateOldEndpointErrorAfterReadyDoesNotInvalidateTheGeneration() {
        val coordinator = RaftAvatarHttpLoadCoordinator()
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        val cdn = coordinator.stateFlow(key).value
        coordinator.reportObserverPhase(key, cdn.generation, RaftAvatarHttpEndpoint.Cdn, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportObserverPhase(key, cdn.generation, RaftAvatarHttpEndpoint.Api, RaftAvatarHttpPainterPhase.Success)
        val ready = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Ready, ready.cache)

        coordinator.reportReadyConsumerError(key, ready.generation, RaftAvatarHttpEndpoint.Cdn)
        assertEquals(ready, coordinator.stateFlow(key).value, "an error for the abandoned CDN URL changes nothing")
        coordinator.reportReadyConsumerError(key, ready.generation, RaftAvatarHttpEndpoint.Api)
        assertEquals(RaftAvatarHttpCacheState.Cooldown, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun observerReportsAreFencedByGenerationAndEndpoint() {
        val coordinator = RaftAvatarHttpLoadCoordinator()
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        val flight = coordinator.stateFlow(key).value

        coordinator.reportObserverPhase(key, flight.generation + 1, flight.endpoint, RaftAvatarHttpPainterPhase.Success)
        coordinator.reportObserverPhase(key, flight.generation, RaftAvatarHttpEndpoint.Api, RaftAvatarHttpPainterPhase.Error)

        assertEquals(flight, coordinator.stateFlow(key).value, "stale or cross-endpoint reports change nothing")
    }

    @Test
    fun stuckOwnerBecomesAdoptableAfterTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                stuckOwnerTimeoutMillis = 100L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val stuckLease = assertNotNull(coordinator.tryAcquire(key))

        // Owner acquired the lease but never reports a painter phase (frozen offscreen
        // composition). Within the timeout a sibling still joins, afterwards it adopts.
        nowMillis += 50L
        assertNull(coordinator.tryAcquire(key), "owner still fresh, sibling must keep joining")
        nowMillis += 100L
        val adopted = assertNotNull(coordinator.tryAcquire(key), "stuck owner must be adoptable")
        assertEquals(stuckLease.generation, adopted.generation, "adoption keeps the generation")
        assertEquals(adopted.ownerToken, coordinator.stateFlow(key).value.ownerToken)

        // The fenced-off old owner can no longer write phases.
        coordinator.reportPainterPhase(key, stuckLease, RaftAvatarHttpPainterPhase.Success)
        assertEquals(RaftAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)

        coordinator.reportPainterPhase(key, adopted, RaftAvatarHttpPainterPhase.Success)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun ownerProgressRefreshesTheStuckTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                stuckOwnerTimeoutMillis = 100L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))

        // A live owner keeps reporting phases, so the stuck deadline keeps moving.
        repeat(3) {
            nowMillis += 90L
            coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Loading)
            assertNull(coordinator.tryAcquire(key), "a reporting owner is not stuck")
        }
        coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Success)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun consecutiveFailuresDoubleTheCooldownUntilTheCap() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        // First failure: base cooldown (10s step) — a transient error recovers quickly.
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error) // Cdn->Api fallback
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error) // Api fails -> cooldown
        var cooling = coordinator.stateFlow(key).value
        assertEquals(RaftAvatarHttpCacheState.Cooldown, cooling.cache)
        assertEquals(
            RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(cooling)
        )

        // Second failure: doubled.
        nowMillis += RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val second = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, second, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, second, RaftAvatarHttpPainterPhase.Error)
        cooling = coordinator.stateFlow(key).value
        assertEquals(
            2 * RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(cooling)
        )

        // Failures keep doubling until they hit the configured cap, then stay there.
        var delay = 2 * RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        repeat(4) {
            nowMillis += delay
            val lease = assertNotNull(coordinator.tryAcquire(key))
            coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Error)
            coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Error)
            delay = (delay * 2).coerceAtMost(60_000L)
            cooling = coordinator.stateFlow(key).value
            assertEquals(delay, coordinator.retryDelayMillis(cooling))
        }
    }

    @Test
    fun successResetsTheConsecutiveFailureBackoff() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        // Two consecutive failures -> backoff is already beyond the base step.
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        nowMillis += RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val second = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, second, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, second, RaftAvatarHttpPainterPhase.Error)

        // A successful generation resets the counter; the next failure starts at the base step.
        nowMillis += 2 * RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val third = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, third, RaftAvatarHttpPainterPhase.Success)
        assertEquals(RaftAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)

        // A ready consumer that still errors drops the entry back into cooldown and counts
        // as a failure. If Success reset the counter, this single failure produces the base
        // step again — not the doubled value the two earlier failures had built up.
        nowMillis += 1L
        coordinator.reportReadyConsumerError(key, coordinator.stateFlow(key).value.generation)
        assertEquals(
            RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

    @Test
    fun readyConsumerErrorUsesTheSameBackoffCounter() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, lease, RaftAvatarHttpPainterPhase.Success)

        // A ready consumer that still errors increments the same consecutive-failure counter.
        coordinator.reportReadyConsumerError(key, coordinator.stateFlow(key).value.generation)
        assertEquals(
            RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

    private fun uploadedAvatarKey(): RaftAvatarHttpLoadKey =
        RaftAvatarHttpLoadKey(
            primarySource = "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp",
            primaryEndpoint = RaftAvatarHttpEndpoint.Cdn,
            apiFallbackSource = "https://api.raft.build/api/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        )
    @Test
    fun retainAfterAllObserversLeftRearmsCooldown() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        coordinator.retain(key)
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        assertEquals(
            RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
        coordinator.release(key)
        coordinator.releaseOwner(key, first)

        // Every observer left (page closed). A new observer arriving during cooldown
        // re-arms the entry for immediate retry instead of waiting out the backoff.
        nowMillis += 1_000L
        coordinator.retain(key)
        assertEquals(0L, coordinator.retryDelayMillis(coordinator.stateFlow(key).value))
        assertNotNull(coordinator.tryAcquire(key))
    }

    @Test
    fun retainWhileObservedKeepsCooldownIntact() {
        var nowMillis = 1_000L
        val coordinator =
            RaftAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        coordinator.retain(key)
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, RaftAvatarHttpPainterPhase.Error)
        coordinator.releaseOwner(key, first)

        // Another observer joins while at least one row is still watching — not a
        // fresh page entry, so the backoff must hold.
        coordinator.retain(key)
        assertEquals(
            RaftAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

}
