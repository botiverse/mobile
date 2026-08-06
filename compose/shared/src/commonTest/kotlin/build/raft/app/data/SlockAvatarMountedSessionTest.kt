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

class SlockAvatarMountedSessionTest {
    @Test
    fun sameFlightFansReloadOutToIndependentCurrentMountTokens() {
        val session = SlockAvatarMountedSession()
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
        val validators = SlockAvatarValidators(eTag = "etag-1", lastModified = "last-1")
        val applied =
            assertIs<SlockAvatarMountedResolution.Applied>(
                session.resolve(
                    flight,
                    SlockAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = null,
                        committedToCommitIdentity = committed,
                        validators = validators,
                        newModelRevision = 1
                    )
                )
            )

        assertEquals(setOf(first, second), applied.transitions.map { it.token }.toSet())
        applied.transitions.forEach { transition ->
            val reload = assertIs<SlockAvatarMountedTransition.Reload>(transition)
            assertNull(reload.expectedFromCommitIdentity)
            assertEquals(committed, reload.committedToCommitIdentity)
            assertEquals(1L, reload.newModelRevision)
        }
        listOf(first, second).forEach { token ->
            assertEquals(
                SlockAvatarMountedSnapshot(key, committed, validators, modelRevision = 1),
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
    fun disposedSoleParticipantMakesLateOutcomeStaleAndRemountStaysFresh() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val old = session.mount(key)
        val oldFlight = assertNotNull(session.beginFlight(old))

        assertTrue(session.dispose(old))
        val remounted = session.mount(key)
        assertNotEquals(old.sessionEpoch, remounted.sessionEpoch)
        assertIs<SlockAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                oldFlight,
                SlockAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = null,
                    committedToCommitIdentity = commitIdentity(1, 'b'),
                    validators = SlockAvatarValidators(eTag = "old-etag"),
                    newModelRevision = 1
                )
            )
        )

        val snapshot = assertNotNull(session.snapshot(remounted))
        assertTrue(snapshot.requiresFullCanonicalBody)
        assertNull(snapshot.commitIdentity)
        assertNull(snapshot.validators)
        assertEquals(0L, snapshot.modelRevision)
        val freshFlight = assertNotNull(session.beginFlight(remounted))
        assertNotEquals(oldFlight.requestGeneration, freshFlight.requestGeneration)
        assertTrue(freshFlight.requiresFullCanonicalBody)
    }

    @Test
    fun disposedJoinedMountIsExcludedWhileRemainingParticipantReceivesOutcome() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val disposed = session.mount(key)
        val current = session.mount(key)
        val flight = assertNotNull(session.beginFlight(disposed))
        assertSame(flight, session.beginFlight(current))

        assertTrue(session.dispose(disposed))
        val committed = commitIdentity(1, 'c')
        val applied =
            assertIs<SlockAvatarMountedResolution.Applied>(
                session.resolve(
                    flight,
                    SlockAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = null,
                        committedToCommitIdentity = committed,
                        validators = SlockAvatarValidators(eTag = "current-only"),
                        newModelRevision = 1
                    )
                )
            )

        assertEquals(listOf(current), applied.transitions.map { it.token })
        assertNull(session.snapshot(disposed))
        assertEquals(committed, session.snapshot(current)?.commitIdentity)
    }

    @Test
    fun contentChangeRevokesActiveFlightBeforeMetadataCommit() {
        val session = SlockAvatarMountedSession()
        val oldKey = avatarKey(contentHex = '1')
        val old = session.mount(oldKey)
        val oldFlight = assertNotNull(session.beginFlight(old))

        val newKey = avatarKey(contentHex = '2')
        val replacement = session.mount(newKey)

        assertFalse(session.isCurrent(old))
        assertNull(session.snapshot(old))
        assertIs<SlockAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                oldFlight,
                SlockAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = null,
                    reason = SlockAvatarMountedKeepReason.TemporaryFailure
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
    fun contentChangeRevokesMountOnlySlotBeforeAnyFlightOrMetadataExists() {
        val session = SlockAvatarMountedSession()
        val old = session.mount(avatarKey(contentHex = '3'))

        val newKey = avatarKey(contentHex = '4')
        val replacement = session.mount(newKey)

        assertFalse(session.isCurrent(old))
        assertNull(session.snapshot(old))
        assertTrue(session.isCurrent(replacement))
        val snapshot = assertNotNull(session.snapshot(replacement))
        assertNull(snapshot.commitIdentity)
        assertNull(snapshot.validators)
        assertEquals(0L, snapshot.modelRevision)
        assertTrue(assertNotNull(session.beginFlight(replacement)).requiresFullCanonicalBody)
    }

    @Test
    fun reloadCasAdvancesExactIdentityAndRejectsWrongExpectedOrRevision() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val firstFlight = assertNotNull(session.beginFlight(mount))
        val g1 = commitIdentity(1, '1')
        assertIs<SlockAvatarMountedResolution.Applied>(
            session.resolve(
                firstFlight,
                SlockAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = null,
                    committedToCommitIdentity = g1,
                    validators = SlockAvatarValidators(eTag = "g1"),
                    newModelRevision = 10
                )
            )
        )

        val wrongExpected = assertNotNull(session.beginFlight(mount))
        assertIs<SlockAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                wrongExpected,
                SlockAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = commitIdentity(1, '2'),
                    committedToCommitIdentity = commitIdentity(2, '3'),
                    validators = SlockAvatarValidators(eTag = "wrong"),
                    newModelRevision = 11
                )
            )
        )
        assertEquals(g1, session.snapshot(mount)?.commitIdentity)
        assertEquals(10L, session.snapshot(mount)?.modelRevision)

        val staleRevision = assertNotNull(session.beginFlight(mount))
        assertIs<SlockAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                staleRevision,
                SlockAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = g1,
                    committedToCommitIdentity = commitIdentity(2, '4'),
                    validators = SlockAvatarValidators(eTag = "g2"),
                    newModelRevision = 10
                )
            )
        )
        assertEquals(g1, session.snapshot(mount)?.commitIdentity)

        val valid = assertNotNull(session.beginFlight(mount))
        val g2 = commitIdentity(2, '5')
        assertIs<SlockAvatarMountedResolution.Applied>(
            session.resolve(
                valid,
                SlockAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = g1,
                    committedToCommitIdentity = g2,
                    validators = SlockAvatarValidators(lastModified = "g2-last"),
                    newModelRevision = 11
                )
            )
        )
        assertEquals(g2, session.snapshot(mount)?.commitIdentity)
        assertEquals(11L, session.snapshot(mount)?.modelRevision)
    }

    @Test
    fun notModifiedAndTemporaryKeepPreserveIdentityValidatorsAndRevision() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, '6')
        val validators = SlockAvatarValidators(eTag = "stable", lastModified = "stable-last")
        commitInitial(session, mount, committed, validators, modelRevision = 7)
        val expected = assertNotNull(session.snapshot(mount))

        SlockAvatarMountedKeepReason.entries.forEach { reason ->
            val flight = assertNotNull(session.beginFlight(mount))
            val applied =
                assertIs<SlockAvatarMountedResolution.Applied>(
                    session.resolve(
                        flight,
                        SlockAvatarMountedOutcome.Keep(
                            expectedCommitIdentity = committed,
                            reason = reason
                        )
                    )
                )
            val transition = assertIs<SlockAvatarMountedTransition.Keep>(applied.transitions.single())
            assertEquals(reason, transition.reason)
            assertEquals(7L, transition.modelRevision)
            assertEquals(expected, session.snapshot(mount))
        }
    }

    @Test
    fun authoritativeClearClosesMountsAndForcesNextFullBodyWithoutValidator() {
        SlockAvatarMountedClearReason.entries
            .filterNot { it == SlockAvatarMountedClearReason.Terminal }
            .forEach { reason ->
                val session = SlockAvatarMountedSession()
                val key = avatarKey()
                val first = session.mount(key)
                val second = session.mount(key)
                val committed = commitIdentity(1, '7')
                commitInitial(
                    session,
                    first,
                    committed,
                    SlockAvatarValidators(eTag = "must-not-survive"),
                    modelRevision = 4
                )
                val flight = assertNotNull(session.beginFlight(first))
                assertSame(flight, session.beginFlight(second))

                val applied =
                    assertIs<SlockAvatarMountedResolution.Applied>(
                        session.resolve(
                            flight,
                            SlockAvatarMountedOutcome.Clear(
                                expectedCommitIdentity = committed,
                                reason = reason
                            )
                        )
                    )
                assertEquals(setOf(first, second), applied.transitions.map { it.token }.toSet())
                assertTrue(applied.transitions.all { it is SlockAvatarMountedTransition.Clear })
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
    fun subjectTerminalRevokesFlightClearsMetadataAndAdvancesEpoch() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, '8')
        commitInitial(
            session,
            mount,
            committed,
            SlockAvatarValidators(lastModified = "old-validator"),
            modelRevision = 3
        )
        val lateFlight = assertNotNull(session.beginFlight(mount))
        val previousEpochs = mount.terminalEpochs

        val terminal = session.retireSubject(key.owner, key.subject)
        assertEquals(listOf(mount), terminal.map { it.token })
        assertTrue(terminal.all { it.reason == SlockAvatarMountedClearReason.Terminal })
        assertFalse(session.isCurrent(mount))
        assertIs<SlockAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                lateFlight,
                SlockAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = committed,
                    reason = SlockAvatarMountedKeepReason.TemporaryFailure
                )
            )
        )

        val remount = session.mount(key)
        assertTrue(remount.terminalEpochs.subject > previousEpochs.subject)
        assertNull(session.snapshot(remount)?.commitIdentity)
        assertNull(session.snapshot(remount)?.validators)
        assertTrue(assertNotNull(session.beginFlight(remount)).requiresFullCanonicalBody)
    }

    @Test
    fun ownerTerminalDoesNotRetireAnotherServerPartition() {
        val session = SlockAvatarMountedSession()
        val ownerAKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'a')
        val ownerBKey = avatarKey(serverId = "server-b", subjectId = "member-b", contentHex = 'b')
        val a = session.mount(ownerAKey)
        val b = session.mount(ownerBKey)
        commitInitial(
            session,
            a,
            commitIdentity(1, 'a'),
            SlockAvatarValidators(eTag = "a-tag"),
            modelRevision = 1
        )
        val bIdentity = commitIdentity(1, 'b')
        val bValidators = SlockAvatarValidators(eTag = "b-tag")
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
    fun mountingAnotherServerPartitionDoesNotRetireExistingMountedState() {
        val session = SlockAvatarMountedSession()
        val firstKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'a')
        val first = session.mount(firstKey)
        val firstIdentity = commitIdentity(1, 'a')
        val firstValidators = SlockAvatarValidators(eTag = "a-tag")
        commitInitial(session, first, firstIdentity, firstValidators, modelRevision = 2)

        val selected =
            session.mount(
                avatarKey(serverId = "server-b", subjectId = "member-b", contentHex = 'b')
            )

        assertTrue(session.isCurrent(first))
        assertEquals(firstIdentity, session.snapshot(first)?.commitIdentity)
        assertEquals(firstValidators, session.snapshot(first)?.validators)
        assertFalse(assertNotNull(session.beginFlight(first)).requiresFullCanonicalBody)
        assertTrue(assertNotNull(session.beginFlight(selected)).requiresFullCanonicalBody)
    }

    @Test
    fun clearAllRevokesEveryScopeWhileNewProcessStartsWithoutHistory() {
        val session = SlockAvatarMountedSession()
        val firstKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'c')
        val secondKey = avatarKey(serverId = "server-b", subjectId = "member-b", contentHex = 'd')
        val first = session.mount(firstKey)
        val second = session.mount(secondKey)
        commitInitial(
            session,
            first,
            commitIdentity(1, 'c'),
            SlockAvatarValidators(eTag = "c-tag"),
            modelRevision = 1
        )
        commitInitial(
            session,
            second,
            commitIdentity(1, 'd'),
            SlockAvatarValidators(eTag = "d-tag"),
            modelRevision = 1
        )

        val cleared = session.clearAll()
        assertEquals(setOf(first, second), cleared.map { it.token }.toSet())
        assertFalse(session.isCurrent(first))
        assertFalse(session.isCurrent(second))
        val remounted = session.mount(firstKey)
        assertTrue(assertNotNull(session.snapshot(remounted)).requiresFullCanonicalBody)

        val restartedSession = SlockAvatarMountedSession()
        val afterRestart = restartedSession.mount(secondKey)
        val request = assertNotNull(restartedSession.beginFlight(afterRestart))
        assertTrue(request.requiresFullCanonicalBody)
        assertNull(request.expectedCommitIdentity)
        assertNull(request.validators)
    }

    @Test
    fun wrongCommitIdentityCannotKeepOrClearCurrentMountedState() {
        val session = SlockAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, 'e')
        val validators = SlockAvatarValidators(eTag = "current")
        commitInitial(session, mount, committed, validators, modelRevision = 5)

        val keepFlight = assertNotNull(session.beginFlight(mount))
        assertIs<SlockAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                keepFlight,
                SlockAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = commitIdentity(1, 'f'),
                    reason = SlockAvatarMountedKeepReason.NotModified
                )
            )
        )
        assertEquals(committed, session.snapshot(mount)?.commitIdentity)

        val clearFlight = assertNotNull(session.beginFlight(mount))
        assertIs<SlockAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                clearFlight,
                SlockAvatarMountedOutcome.Clear(
                    expectedCommitIdentity = commitIdentity(1, '0'),
                    reason = SlockAvatarMountedClearReason.ResourceAbsent
                )
            )
        )
        assertTrue(session.isCurrent(mount))
        assertEquals(committed, session.snapshot(mount)?.commitIdentity)
        assertEquals(validators, session.snapshot(mount)?.validators)
    }

    private fun commitInitial(
        session: SlockAvatarMountedSession,
        mount: SlockAvatarMountToken,
        identity: SlockAvatarCommitIdentity,
        validators: SlockAvatarValidators?,
        modelRevision: Long
    ) {
        val flight = assertNotNull(session.beginFlight(mount))
        assertIs<SlockAvatarMountedResolution.Applied>(
            session.resolve(
                flight,
                SlockAvatarMountedOutcome.Reload(
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
    ): SlockAvatarCacheKey =
        SlockAvatarCacheKey(
            owner =
                SlockAvatarCacheOwner(
                    environmentId = "https://api.raft.test",
                    accountId = "account-a",
                    serverId = serverId
                ),
            subject = SlockAvatarCacheSubject.Member(MemberKind.Human, subjectId),
            content =
                SlockAvatarContentIdentity(
                    kind = SlockAvatarContentKind.RaftImmutable,
                    fingerprint = contentHex.toString().repeat(64)
                )
        )

    private fun commitIdentity(generation: Long, nonceHex: Char): SlockAvatarCommitIdentity =
        SlockAvatarCommitIdentity(generation, nonceHex.toString().repeat(64))
}
