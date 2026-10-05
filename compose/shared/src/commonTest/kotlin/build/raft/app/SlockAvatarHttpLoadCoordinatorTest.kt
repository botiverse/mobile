package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAvatarHttpLoadCoordinatorTest {
    @Test
    fun genericIconIsAFailureAffordanceNotALoadingPlaceholder() {
        // task #343: cold start must go blank -> real avatar, never default icon -> real avatar.
        assertFalse(slockAvatarHttpShowsFallback(SlockAvatarHttpLoadSnapshot()))
        assertFalse(
            slockAvatarHttpShowsFallback(
                SlockAvatarHttpLoadSnapshot(
                    cache = SlockAvatarHttpCacheState.InFlight,
                    painter = SlockAvatarHttpPainterPhase.Loading
                )
            )
        )
        assertFalse(
            slockAvatarHttpShowsFallback(
                SlockAvatarHttpLoadSnapshot(
                    cache = SlockAvatarHttpCacheState.Ready,
                    painter = SlockAvatarHttpPainterPhase.Success
                )
            )
        )
        assertTrue(
            slockAvatarHttpShowsFallback(SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Cooldown))
        )
        assertTrue(
            slockAvatarHttpShowsFallback(
                SlockAvatarHttpLoadSnapshot(
                    cache = SlockAvatarHttpCacheState.Ready,
                    painter = SlockAvatarHttpPainterPhase.Error
                )
            )
        )
    }

    @Test
    fun sameUploadedAvatarRowsJoinOneActiveRequestAndReuseReadyCache() {
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
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

        coordinator.reportPainterPhase(key, firstRowLease, SlockAvatarHttpPainterPhase.Loading)
        coordinator.reportPainterPhase(key, firstRowLease, SlockAvatarHttpPainterPhase.Success)

        val ready = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.Ready, ready.cache)
        assertEquals(SlockAvatarHttpPainterPhase.Success, ready.painter)
        assertNull(coordinator.tryAcquire(key), "ready rows must reuse Kuikly's shared image cache")
    }

    @Test
    fun ordinaryUrlIsWithheldOnlyWhileIdleOrCoolingDown() {
        val url = "https://cdn.example/avatar.webp"
        val malformedManagedModel = "slock-avatar-render-v1:not-base64"
        val idle = SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Idle)
        val joined =
            SlockAvatarHttpLoadSnapshot(
                cache = SlockAvatarHttpCacheState.InFlight,
                generation = 1L,
                ownerToken = 7L
            )
        val ready = SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Ready)
        val coolingDown = SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Cooldown)

        assertNull(slockAvatarHttpAdmittedImageModel(url, idle, ownsCurrentRequest = false))
        assertNull(slockAvatarHttpAdmittedImageModel(url, coolingDown, ownsCurrentRequest = false))
        // A row that joined someone else's flight renders it too, instead of waiting for the owner
        // to be drawn (HarmonyOS 1.13.0: every server avatar stayed black).
        assertEquals(url, slockAvatarHttpAdmittedImageModel(url, joined, ownsCurrentRequest = false))
        // The durable refresh still downloads once: only the owner (or a ready generation) may.
        assertFalse(slockAvatarHttpLoadIsAdmitted(joined, ownsCurrentRequest = false))
        assertNull(
            slockAvatarHttpAdmittedImageModel(
                malformedManagedModel,
                idle,
                ownsCurrentRequest = false
            )
        )
        assertEquals(url, slockAvatarHttpAdmittedImageModel(url, joined, ownsCurrentRequest = true))
        assertEquals(url, slockAvatarHttpAdmittedImageModel(url, ready, ownsCurrentRequest = false))
    }

    @Test
    fun dnsFailureFallsBackThenCooldownAllowsSameUrlToRecover() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val firstLease = assertNotNull(coordinator.tryAcquire(key))

        coordinator.reportPainterPhase(key, firstLease, SlockAvatarHttpPainterPhase.Error)
        val apiAttempt = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.InFlight, apiAttempt.cache)
        assertEquals(SlockAvatarHttpEndpoint.Api, apiAttempt.endpoint)
        assertEquals(firstLease.ownerToken, apiAttempt.ownerToken)
        assertNull(coordinator.tryAcquire(key), "fallback remains the same single-flight owner")

        coordinator.reportPainterPhase(key, firstLease, SlockAvatarHttpPainterPhase.Error)
        val coolingDown = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.Cooldown, coolingDown.cache)
        assertEquals(500L, coordinator.retryDelayMillis(coolingDown))
        assertNull(coordinator.tryAcquire(key))

        nowMillis += 500L
        val recoveryLease = assertNotNull(coordinator.tryAcquire(key))
        assertEquals(firstLease.generation + 1L, recoveryLease.generation)
        assertEquals(SlockAvatarHttpEndpoint.Cdn, coordinator.stateFlow(key).value.endpoint)
        coordinator.reportPainterPhase(key, recoveryLease, SlockAvatarHttpPainterPhase.Success)
        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun lateRowLocalFailureCannotOverrideNewerOwnerOrSuccessfulGeneration() {
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Success)

        coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Error)

        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
        assertEquals(SlockAvatarHttpPainterPhase.Success, coordinator.stateFlow(key).value.painter)
    }

    @Test
    fun ownerDisposalInvalidatesOnlyItsInFlightGeneration() {
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                nowMillis = { 1_000L }
            )
        val key = uploadedAvatarKey()
        val abandoned = assertNotNull(coordinator.tryAcquire(key))
        assertNull(coordinator.tryAcquire(key), "a mounted sibling joins rather than starting a duplicate")

        coordinator.releaseOwner(key, abandoned)
        val replacement = assertNotNull(coordinator.tryAcquire(key))
        assertEquals(abandoned.generation + 1L, replacement.generation)

        coordinator.reportPainterPhase(key, abandoned, SlockAvatarHttpPainterPhase.Success)
        assertEquals(replacement.ownerToken, coordinator.stateFlow(key).value.ownerToken)
        assertEquals(SlockAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun ownerDisposalWithRemainingObserversOrphansInsteadOfResetting() {
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
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
        assertEquals(SlockAvatarHttpCacheState.InFlight, orphaned.cache)
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
            SlockAvatarHttpLoadCoordinator(
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

        coordinator.reportPainterPhase(oldKey, oldLease, SlockAvatarHttpPainterPhase.Success)

        assertEquals(SlockAvatarHttpCacheState.Idle, coordinator.stateFlow(oldKey).value.cache)
        assertEquals(newLease.ownerToken, coordinator.stateFlow(newKey).value.ownerToken)
        assertEquals(SlockAvatarHttpCacheState.InFlight, coordinator.stateFlow(newKey).value.cache)
    }

    @Test
    fun terminalEntriesAreBoundedAfterRowsDetach() {
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
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
            coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Success)
            coordinator.release(key)
        }

        assertEquals(2, coordinator.entryCount())
    }

    @Test
    fun rowWaitingOnAnotherOwnerComesBackAfterTheStuckTimeout() {
        val owned = SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.InFlight, ownerToken = 7L)
        // HarmonyOS 1.13.0: the visible server avatar waited on a silent owner forever because
        // only Idle/Cooldown/orphaned flights ever asked for a lease.
        assertEquals(
            SlockAvatarHttpAcquireAttempt.AfterStuckOwnerTimeout,
            slockAvatarHttpAcquireAttempt(owned, ownsCurrentRequest = false)
        )
        assertEquals(
            SlockAvatarHttpAcquireAttempt.None,
            slockAvatarHttpAcquireAttempt(owned, ownsCurrentRequest = true)
        )
        assertEquals(
            SlockAvatarHttpAcquireAttempt.Now,
            slockAvatarHttpAcquireAttempt(owned.copy(ownerToken = null), ownsCurrentRequest = false)
        )
        assertEquals(
            SlockAvatarHttpAcquireAttempt.Now,
            slockAvatarHttpAcquireAttempt(SlockAvatarHttpLoadSnapshot(), ownsCurrentRequest = false)
        )
        assertEquals(
            SlockAvatarHttpAcquireAttempt.Now,
            slockAvatarHttpAcquireAttempt(
                SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Cooldown),
                ownsCurrentRequest = false
            )
        )
        assertEquals(
            SlockAvatarHttpAcquireAttempt.None,
            slockAvatarHttpAcquireAttempt(
                SlockAvatarHttpLoadSnapshot(cache = SlockAvatarHttpCacheState.Ready),
                ownsCurrentRequest = false
            )
        )
    }

    @Test
    fun recheckDelayLandsPastTheStuckTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(stuckOwnerTimeoutMillis = 100L, nowMillis = { nowMillis })
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        nowMillis += coordinator.stuckOwnerRecheckDelayMillis
        assertNotNull(coordinator.tryAcquire(key), "one recheck delay later a silent owner is adoptable")
    }

    @Test
    fun observerSuccessSettlesTheFlightForEveryRow() {
        val coordinator = SlockAvatarHttpLoadCoordinator()
        val key = uploadedAvatarKey()
        val ownerLease = assertNotNull(coordinator.tryAcquire(key))
        val flight = coordinator.stateFlow(key).value

        // The owner is composed but never drawn, so it never reports. A drawn observer does.
        coordinator.reportObserverPhase(key, flight.generation, flight.endpoint, SlockAvatarHttpPainterPhase.Loading)
        assertEquals(SlockAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)
        coordinator.reportObserverPhase(key, flight.generation, flight.endpoint, SlockAvatarHttpPainterPhase.Success)

        val settled = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.Ready, settled.cache)
        assertNull(settled.ownerToken)
        // A late report from the original owner no longer changes the settled generation.
        coordinator.reportPainterPhase(key, ownerLease, SlockAvatarHttpPainterPhase.Error)
        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun observerErrorFallsBackToApiThenCoolsDown() {
        var nowMillis = 1_000L
        val coordinator = SlockAvatarHttpLoadCoordinator(nowMillis = { nowMillis })
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        val cdn = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpEndpoint.Cdn, cdn.endpoint)

        coordinator.reportObserverPhase(key, cdn.generation, cdn.endpoint, SlockAvatarHttpPainterPhase.Error)
        val api = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpEndpoint.Api, api.endpoint)
        assertEquals(SlockAvatarHttpCacheState.InFlight, api.cache)

        coordinator.reportObserverPhase(key, api.generation, api.endpoint, SlockAvatarHttpPainterPhase.Error)
        val cooled = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.Cooldown, cooled.cache)
        assertTrue(slockAvatarHttpShowsFallback(cooled))
    }

    @Test
    fun observerReportsAreFencedByGenerationAndEndpoint() {
        val coordinator = SlockAvatarHttpLoadCoordinator()
        val key = uploadedAvatarKey()
        assertNotNull(coordinator.tryAcquire(key))
        val flight = coordinator.stateFlow(key).value

        coordinator.reportObserverPhase(key, flight.generation + 1, flight.endpoint, SlockAvatarHttpPainterPhase.Success)
        coordinator.reportObserverPhase(key, flight.generation, SlockAvatarHttpEndpoint.Api, SlockAvatarHttpPainterPhase.Error)

        assertEquals(flight, coordinator.stateFlow(key).value, "stale or cross-endpoint reports change nothing")
    }

    @Test
    fun stuckOwnerBecomesAdoptableAfterTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
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
        coordinator.reportPainterPhase(key, stuckLease, SlockAvatarHttpPainterPhase.Success)
        assertEquals(SlockAvatarHttpCacheState.InFlight, coordinator.stateFlow(key).value.cache)

        coordinator.reportPainterPhase(key, adopted, SlockAvatarHttpPainterPhase.Success)
        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun ownerProgressRefreshesTheStuckTimeout() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 500L,
                stuckOwnerTimeoutMillis = 100L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))

        // A live owner keeps reporting phases, so the stuck deadline keeps moving.
        repeat(3) {
            nowMillis += 90L
            coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Loading)
            assertNull(coordinator.tryAcquire(key), "a reporting owner is not stuck")
        }
        coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Success)
        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)
    }

    @Test
    fun consecutiveFailuresDoubleTheCooldownUntilTheCap() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        // First failure: base cooldown (10s step) — a transient error recovers quickly.
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error) // Cdn->Api fallback
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error) // Api fails -> cooldown
        var cooling = coordinator.stateFlow(key).value
        assertEquals(SlockAvatarHttpCacheState.Cooldown, cooling.cache)
        assertEquals(
            SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(cooling)
        )

        // Second failure: doubled.
        nowMillis += SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val second = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, second, SlockAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, second, SlockAvatarHttpPainterPhase.Error)
        cooling = coordinator.stateFlow(key).value
        assertEquals(
            2 * SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(cooling)
        )

        // Failures keep doubling until they hit the configured cap, then stay there.
        var delay = 2 * SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        repeat(4) {
            nowMillis += delay
            val lease = assertNotNull(coordinator.tryAcquire(key))
            coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Error)
            coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Error)
            delay = (delay * 2).coerceAtMost(60_000L)
            cooling = coordinator.stateFlow(key).value
            assertEquals(delay, coordinator.retryDelayMillis(cooling))
        }
    }

    @Test
    fun successResetsTheConsecutiveFailureBackoff() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        // Two consecutive failures -> backoff is already beyond the base step.
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        nowMillis += SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val second = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, second, SlockAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, second, SlockAvatarHttpPainterPhase.Error)

        // A successful generation resets the counter; the next failure starts at the base step.
        nowMillis += 2 * SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS
        val third = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, third, SlockAvatarHttpPainterPhase.Success)
        assertEquals(SlockAvatarHttpCacheState.Ready, coordinator.stateFlow(key).value.cache)

        // A ready consumer that still errors drops the entry back into cooldown and counts
        // as a failure. If Success reset the counter, this single failure produces the base
        // step again — not the doubled value the two earlier failures had built up.
        nowMillis += 1L
        coordinator.reportReadyConsumerError(key, coordinator.stateFlow(key).value.generation)
        assertEquals(
            SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

    @Test
    fun readyConsumerErrorUsesTheSameBackoffCounter() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()
        val lease = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, lease, SlockAvatarHttpPainterPhase.Success)

        // A ready consumer that still errors increments the same consecutive-failure counter.
        coordinator.reportReadyConsumerError(key, coordinator.stateFlow(key).value.generation)
        assertEquals(
            SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

    private fun uploadedAvatarKey(): SlockAvatarHttpLoadKey =
        SlockAvatarHttpLoadKey(
            primarySource = "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp",
            primaryEndpoint = SlockAvatarHttpEndpoint.Cdn,
            apiFallbackSource = "https://api.raft.build/api/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        )
    @Test
    fun retainAfterAllObserversLeftRearmsCooldown() {
        var nowMillis = 1_000L
        val coordinator =
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        coordinator.retain(key)
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        assertEquals(
            SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
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
            SlockAvatarHttpLoadCoordinator(
                failureCooldownMillis = 60_000L,
                nowMillis = { nowMillis }
            )
        val key = uploadedAvatarKey()

        coordinator.retain(key)
        val first = assertNotNull(coordinator.tryAcquire(key))
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        coordinator.reportPainterPhase(key, first, SlockAvatarHttpPainterPhase.Error)
        coordinator.releaseOwner(key, first)

        // Another observer joins while at least one row is still watching — not a
        // fresh page entry, so the backoff must hold.
        coordinator.retain(key)
        assertEquals(
            SlockAvatarHttpLoadCoordinator.MAX_SINGLE_FAILURE_COOLDOWN_MILLIS,
            coordinator.retryDelayMillis(coordinator.stateFlow(key).value)
        )
    }

}
