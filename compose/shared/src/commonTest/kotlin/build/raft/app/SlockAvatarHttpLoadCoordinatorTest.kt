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
    fun ordinaryUrlAndMalformedManagedModelRemainBehindHttpGate() {
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

        assertNull(slockAvatarHttpAdmittedImageModel(url, idle, ownsCurrentRequest = false))
        assertNull(slockAvatarHttpAdmittedImageModel(url, joined, ownsCurrentRequest = false))
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

    private fun uploadedAvatarKey(): SlockAvatarHttpLoadKey =
        SlockAvatarHttpLoadKey(
            primarySource = "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp",
            primaryEndpoint = SlockAvatarHttpEndpoint.Cdn,
            apiFallbackSource = "https://api.raft.build/api/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        )
}
