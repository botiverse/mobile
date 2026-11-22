package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftAvatarMountedSessionTest {
    @Test
    fun sameFlightFansReloadOutToIndependentCurrentMountTokens() {
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val first = session.mount(key)
        val second = session.mount(key)

        assertNotEquals(first.mountId, second.mountId)
        assertNotEquals(first.sessionEpoch, second.sessionEpoch)
        val flight = assertNotNull(session.beginFlight(first))
        assertSame(flight, session.beginFlight(second))
        assertTrue(flight.requiresFullCanonicalBody)
        assertNull(flight.expectedCommitIdentity)
        assertNull(flight.validators)

        val committed = commitIdentity(1, 'a')
        val validators = RaftAvatarValidators(eTag = "etag-1", lastModified = "last-1")
        val applied =
            assertIs<RaftAvatarMountedResolution.Applied>(
                session.resolve(
                    flight,
                    RaftAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = null,
                        committedToCommitIdentity = committed,
                        validators = validators,
                        newModelRevision = 1
                    )
                )
            )

        assertEquals(setOf(first, second), applied.transitions.map { it.token }.toSet())
        applied.transitions.forEach { transition ->
            val reload = assertIs<RaftAvatarMountedTransition.Reload>(transition)
            assertNull(reload.expectedFromCommitIdentity)
            assertEquals(committed, reload.committedToCommitIdentity)
            assertEquals(1L, reload.newModelRevision)
        }
        listOf(first, second).forEach { token ->
            assertEquals(
                RaftAvatarMountedSnapshot(key, committed, validators, modelRevision = 1),
                session.snapshot(token)
            )
        }

        val next = assertNotNull(session.beginFlight(first))
        assertFalse(next.requiresFullCanonicalBody)
        assertEquals(committed, next.expectedCommitIdentity)
        assertEquals(validators, next.validators)
        assertEquals(1L, next.expectedModelRevision)
    }

    @Test
    fun contentChangeRevokesActiveFlightBeforeMetadataCommit() {
        val session = RaftAvatarMountedSession()
        val oldKey = avatarKey(contentHex = '1')
        val old = session.mount(oldKey)
        val oldFlight = assertNotNull(session.beginFlight(old))

        val newKey = avatarKey(contentHex = '2')
        val replacement = session.mount(newKey)

        assertFalse(session.isCurrent(old))
        assertNull(session.snapshot(old))
        assertIs<RaftAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                oldFlight,
                RaftAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = null,
                    reason = RaftAvatarMountedKeepReason.TemporaryFailure
                )
            )
        )
        val newFlight = assertNotNull(session.beginFlight(replacement))
        assertEquals(newKey, newFlight.key)
        assertNull(newFlight.expectedCommitIdentity)
        assertNull(newFlight.validators)
        assertTrue(newFlight.requiresFullCanonicalBody)
    }

    @Test
    fun authoritativeClearClosesMountsAndForcesNextFullBodyWithoutValidator() {
        RaftAvatarMountedClearReason.entries
            .filterNot { it == RaftAvatarMountedClearReason.Terminal }
            .forEach { reason ->
                val session = RaftAvatarMountedSession()
                val key = avatarKey()
                val first = session.mount(key)
                val second = session.mount(key)
                val committed = commitIdentity(1, '7')
                commitInitial(
                    session,
                    first,
                    committed,
                    RaftAvatarValidators(eTag = "must-not-survive"),
                    modelRevision = 4
                )
                val flight = assertNotNull(session.beginFlight(first))
                assertSame(flight, session.beginFlight(second))

                val applied =
                    assertIs<RaftAvatarMountedResolution.Applied>(
                        session.resolve(
                            flight,
                            RaftAvatarMountedOutcome.Clear(
                                expectedCommitIdentity = committed,
                                reason = reason
                            )
                        )
                    )
                assertEquals(setOf(first, second), applied.transitions.map { it.token }.toSet())
                assertTrue(applied.transitions.all { it is RaftAvatarMountedTransition.Clear })
                assertFalse(session.isCurrent(first))
                assertFalse(session.isCurrent(second))

                val afterClear = session.mount(key)
                val snapshot = assertNotNull(session.snapshot(afterClear))
                assertNull(snapshot.commitIdentity)
                assertNull(snapshot.validators)
                assertTrue(snapshot.requiresFullCanonicalBody)
                val fullFetch = assertNotNull(session.beginFlight(afterClear))
                assertNull(fullFetch.expectedCommitIdentity)
                assertNull(fullFetch.validators)
                assertTrue(fullFetch.requiresFullCanonicalBody)
            }
    }

    @Test
    fun ownerTerminalDoesNotRetireAnotherServerPartition() {
        val session = RaftAvatarMountedSession()
        val ownerAKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'a')
        val ownerBKey = avatarKey(serverId = "server-b", subjectId = "member-b", contentHex = 'b')
        val a = session.mount(ownerAKey)
        val b = session.mount(ownerBKey)
        commitInitial(
            session,
            a,
            commitIdentity(1, 'a'),
            RaftAvatarValidators(eTag = "a-tag"),
            modelRevision = 1
        )
        val bIdentity = commitIdentity(1, 'b')
        val bValidators = RaftAvatarValidators(eTag = "b-tag")
        commitInitial(session, b, bIdentity, bValidators, modelRevision = 1)

        val retired = session.retireOwner(ownerAKey.owner)
        assertEquals(listOf(a), retired.map { it.token })
        assertFalse(session.isCurrent(a))
        assertTrue(session.isCurrent(b))
        assertEquals(bIdentity, session.snapshot(b)?.commitIdentity)
        assertEquals(bValidators, session.snapshot(b)?.validators)

        val remountA = session.mount(ownerAKey)
        assertTrue(assertNotNull(session.snapshot(remountA)).requiresFullCanonicalBody)
        assertFalse(assertNotNull(session.beginFlight(b)).requiresFullCanonicalBody)
    }

    @Test
    fun clearAllRevokesEveryScopeWhileNewProcessStartsWithoutHistory() {
        val session = RaftAvatarMountedSession()
        val firstKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'c')
        val secondKey = avatarKey(serverId = "server-b", subjectId = "member-b", contentHex = 'd')
        val first = session.mount(firstKey)
        val second = session.mount(secondKey)
        commitInitial(
            session,
            first,
            commitIdentity(1, 'c'),
            RaftAvatarValidators(eTag = "c-tag"),
            modelRevision = 1
        )
        commitInitial(
            session,
            second,
            commitIdentity(1, 'd'),
            RaftAvatarValidators(eTag = "d-tag"),
            modelRevision = 1
        )

        val cleared = session.clearAll()
        assertEquals(setOf(first, second), cleared.map { it.token }.toSet())
        assertFalse(session.isCurrent(first))
        assertFalse(session.isCurrent(second))
        val remounted = session.mount(firstKey)
        assertTrue(assertNotNull(session.snapshot(remounted)).requiresFullCanonicalBody)

        val restartedSession = RaftAvatarMountedSession()
        val afterRestart = restartedSession.mount(secondKey)
        val request = assertNotNull(restartedSession.beginFlight(afterRestart))
        assertTrue(request.requiresFullCanonicalBody)
        assertNull(request.expectedCommitIdentity)
        assertNull(request.validators)
    }

    @Test
    fun legacyWrongPartitionAndDelayedDurableSeedsFailClosed() {
        val key = avatarKey()
        val exact = durableMetadata(key, commitIdentity(1, '1'), modelRevision = 1, eTag = "exact")

        val legacySession = RaftAvatarMountedSession()
        val legacyToken = legacySession.mount(key)
        val legacy = exact.copy(commitIdentity = null, modelRevision = 0L, eTag = "legacy")
        assertFalse(legacySession.seedDurable(legacyToken, legacy))
        val legacyFlight = assertNotNull(legacySession.beginFlight(legacyToken))
        assertTrue(legacyFlight.requiresFullCanonicalBody)
        assertNull(legacyFlight.validators)

        val wrongSession = RaftAvatarMountedSession()
        val wrongToken = wrongSession.mount(key)
        assertFalse(
            wrongSession.seedDurable(
                wrongToken,
                durableMetadata(
                    avatarKey(serverId = "server-b"),
                    commitIdentity(1, '2'),
                    modelRevision = 1,
                    eTag = "wrong"
                )
            )
        )

        val delayedSession = RaftAvatarMountedSession()
        val delayedToken = delayedSession.mount(key)
        val active = assertNotNull(delayedSession.beginFlight(delayedToken))
        assertFalse(delayedSession.seedDurable(delayedToken, exact))
        assertSame(active, delayedSession.beginFlight(delayedToken))
    }

    private fun commitInitial(
        session: RaftAvatarMountedSession,
        mount: RaftAvatarMountToken,
        identity: RaftAvatarCommitIdentity,
        validators: RaftAvatarValidators?,
        modelRevision: Long
    ) {
        val flight = assertNotNull(session.beginFlight(mount))
        assertIs<RaftAvatarMountedResolution.Applied>(
            session.resolve(
                flight,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = null,
                    committedToCommitIdentity = identity,
                    validators = validators,
                    newModelRevision = modelRevision
                )
            )
        )
    }

    private fun avatarKey(
        serverId: String = "server-a",
        subjectId: String = "member-a",
        contentHex: Char = 'a'
    ): RaftAvatarCacheKey =
        RaftAvatarCacheKey(
            owner =
                RaftAvatarCacheOwner(
                    environmentId = "https://api.raft.test",
                    accountId = "account-a",
                    serverId = serverId
                ),
            subject = RaftAvatarCacheSubject.Member(MemberKind.Human, subjectId),
            content =
                RaftAvatarContentIdentity(
                    kind = RaftAvatarContentKind.RaftImmutable,
                    fingerprint = contentHex.toString().repeat(64)
                )
        )

    private fun durableMetadata(
        key: RaftAvatarCacheKey,
        identity: RaftAvatarCommitIdentity,
        modelRevision: Long,
        eTag: String?
    ): RaftAvatarCacheMetadata =
        RaftAvatarCacheMetadata(
            key = key,
            eTag = eTag,
            fetchedAtMs = 100L,
            validatedAtMs = 100L,
            freshUntilMs = 200L,
            staleUntilMs = 300L,
            sizeBytes = 128L,
            contentType = "image/webp",
            commitIdentity = identity,
            modelRevision = modelRevision
        )

    private fun commitIdentity(generation: Long, nonceHex: Char): RaftAvatarCommitIdentity =
        RaftAvatarCommitIdentity(generation, nonceHex.toString().repeat(64))
}
