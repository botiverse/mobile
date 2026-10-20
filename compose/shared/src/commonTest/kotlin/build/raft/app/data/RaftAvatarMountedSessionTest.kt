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
    fun joinedMountsShareRequestNonceAndSuccessorFlightGetsFreshNonce() {
        var nonce = 0
        val session =
            RaftAvatarMountedSession {
                (++nonce).toString(16).padStart(64, '0')
            }
        val first = session.mount(avatarKey())
        val second = session.mount(avatarKey())

        val joined = assertNotNull(session.beginFlight(first))
        assertSame(joined, session.beginFlight(second))
        assertEquals("1".padStart(64, '0'), joined.requestNonce)
        assertIs<RaftAvatarMountedResolution.Applied>(
            session.resolve(
                joined,
                RaftAvatarMountedOutcome.Keep(null, RaftAvatarMountedKeepReason.TemporaryFailure)
            )
        )

        val successor = assertNotNull(session.beginFlight(first))
        assertEquals("2".padStart(64, '0'), successor.requestNonce)
        assertNotEquals(joined.requestNonce, successor.requestNonce)
    }

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
    fun disposedSoleParticipantMakesLateOutcomeStaleAndRemountStaysFresh() {
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val old = session.mount(key)
        val oldFlight = assertNotNull(session.beginFlight(old))

        assertTrue(session.dispose(old))
        assertFalse(session.dispose(old))
        val remounted = session.mount(key)
        assertNotEquals(old.sessionEpoch, remounted.sessionEpoch)
        assertIs<RaftAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                oldFlight,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = null,
                    committedToCommitIdentity = commitIdentity(1, 'b'),
                    validators = RaftAvatarValidators(eTag = "old-etag"),
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
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val disposed = session.mount(key)
        val current = session.mount(key)
        val flight = assertNotNull(session.beginFlight(disposed))
        assertSame(flight, session.beginFlight(current))

        assertTrue(session.dispose(disposed))
        val committed = commitIdentity(1, 'c')
        val applied =
            assertIs<RaftAvatarMountedResolution.Applied>(
                session.resolve(
                    flight,
                    RaftAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = null,
                        committedToCommitIdentity = committed,
                        validators = RaftAvatarValidators(eTag = "current-only"),
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
    fun contentChangeRevokesMountOnlySlotBeforeAnyFlightOrMetadataExists() {
        val session = RaftAvatarMountedSession()
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
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val firstFlight = assertNotNull(session.beginFlight(mount))
        val g1 = commitIdentity(1, '1')
        assertIs<RaftAvatarMountedResolution.Applied>(
            session.resolve(
                firstFlight,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = null,
                    committedToCommitIdentity = g1,
                    validators = RaftAvatarValidators(eTag = "g1"),
                    newModelRevision = 10
                )
            )
        )

        val wrongExpected = assertNotNull(session.beginFlight(mount))
        assertIs<RaftAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                wrongExpected,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = commitIdentity(1, '2'),
                    committedToCommitIdentity = commitIdentity(2, '3'),
                    validators = RaftAvatarValidators(eTag = "wrong"),
                    newModelRevision = 11
                )
            )
        )
        assertEquals(g1, session.snapshot(mount)?.commitIdentity)
        assertEquals(10L, session.snapshot(mount)?.modelRevision)

        val staleRevision = assertNotNull(session.beginFlight(mount))
        assertIs<RaftAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                staleRevision,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = g1,
                    committedToCommitIdentity = commitIdentity(2, '4'),
                    validators = RaftAvatarValidators(eTag = "g2"),
                    newModelRevision = 10
                )
            )
        )
        assertEquals(g1, session.snapshot(mount)?.commitIdentity)

        val valid = assertNotNull(session.beginFlight(mount))
        val g2 = commitIdentity(2, '5')
        assertIs<RaftAvatarMountedResolution.Applied>(
            session.resolve(
                valid,
                RaftAvatarMountedOutcome.Reload(
                    expectedFromCommitIdentity = g1,
                    committedToCommitIdentity = g2,
                    validators = RaftAvatarValidators(lastModified = "g2-last"),
                    newModelRevision = 11
                )
            )
        )
        assertEquals(g2, session.snapshot(mount)?.commitIdentity)
        assertEquals(11L, session.snapshot(mount)?.modelRevision)
    }

    @Test
    fun notModifiedAndTemporaryKeepPreserveIdentityValidatorsAndRevision() {
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, '6')
        val validators = RaftAvatarValidators(eTag = "stable", lastModified = "stable-last")
        commitInitial(session, mount, committed, validators, modelRevision = 7)
        val expected = assertNotNull(session.snapshot(mount))

        RaftAvatarMountedKeepReason.entries.forEach { reason ->
            val flight = assertNotNull(session.beginFlight(mount))
            val applied =
                assertIs<RaftAvatarMountedResolution.Applied>(
                    session.resolve(
                        flight,
                        RaftAvatarMountedOutcome.Keep(
                            expectedCommitIdentity = committed,
                            reason = reason
                        )
                    )
                )
            val transition = assertIs<RaftAvatarMountedTransition.Keep>(applied.transitions.single())
            assertEquals(reason, transition.reason)
            assertEquals(7L, transition.modelRevision)
            assertEquals(expected, session.snapshot(mount))
        }
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
    fun subjectTerminalRevokesFlightClearsMetadataAndAdvancesEpoch() {
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, '8')
        commitInitial(
            session,
            mount,
            committed,
            RaftAvatarValidators(lastModified = "old-validator"),
            modelRevision = 3
        )
        val lateFlight = assertNotNull(session.beginFlight(mount))
        val previousEpochs = mount.terminalEpochs

        val terminal = session.retireSubject(key.owner, key.subject)
        assertEquals(listOf(mount), terminal.map { it.token })
        assertTrue(terminal.all { it.reason == RaftAvatarMountedClearReason.Terminal })
        assertFalse(session.isCurrent(mount))
        assertIs<RaftAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                lateFlight,
                RaftAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = committed,
                    reason = RaftAvatarMountedKeepReason.TemporaryFailure
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
    fun mountingAnotherServerPartitionDoesNotRetireExistingMountedState() {
        val session = RaftAvatarMountedSession()
        val firstKey = avatarKey(serverId = "server-a", subjectId = "member-a", contentHex = 'a')
        val first = session.mount(firstKey)
        val firstIdentity = commitIdentity(1, 'a')
        val firstValidators = RaftAvatarValidators(eTag = "a-tag")
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
    fun exactDurableIdentitySeedsOneUntouchedMountAfterRestart() {
        val key = avatarKey()
        val identity = commitIdentity(7, '7')
        val durable = durableMetadata(key, identity, modelRevision = 11, eTag = "restart-etag")
        val restarted = RaftAvatarMountedSession()
        val token = restarted.mount(key)

        assertTrue(restarted.seedDurable(token, durable))
        val snapshot = assertNotNull(restarted.snapshot(token))
        assertEquals(identity, snapshot.commitIdentity)
        assertEquals(11L, snapshot.modelRevision)
        assertEquals("restart-etag", snapshot.validators?.eTag)
        val flight = assertNotNull(restarted.beginFlight(token))
        assertEquals(identity, flight.expectedCommitIdentity)
        assertEquals(11L, flight.expectedModelRevision)
        assertEquals("restart-etag", flight.validators?.eTag)
        assertFalse(flight.requiresFullCanonicalBody)
    }

    @Test
    fun missingNativeBodyKeepsExpectedIdentityButDropsValidatorsForFullBody() {
        val session = RaftAvatarMountedSession()
        val token = session.mount(avatarKey())
        val identity = commitIdentity(7, '8')

        assertTrue(session.seedPrepared(token, identity, validators = null, modelRevision = 11L))

        val flight = assertNotNull(session.beginFlight(token))
        assertEquals(identity, flight.expectedCommitIdentity)
        assertEquals(11L, flight.expectedModelRevision)
        assertNull(flight.validators)
        assertTrue(flight.requiresFullCanonicalBody)
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

    @Test
    fun wrongCommitIdentityCannotKeepOrClearCurrentMountedState() {
        val session = RaftAvatarMountedSession()
        val key = avatarKey()
        val mount = session.mount(key)
        val committed = commitIdentity(1, 'e')
        val validators = RaftAvatarValidators(eTag = "current")
        commitInitial(session, mount, committed, validators, modelRevision = 5)

        val keepFlight = assertNotNull(session.beginFlight(mount))
        assertIs<RaftAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                keepFlight,
                RaftAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = commitIdentity(1, 'f'),
                    reason = RaftAvatarMountedKeepReason.NotModified
                )
            )
        )
        assertIs<RaftAvatarMountedResolution.RejectedStaleFlight>(
            session.resolve(
                keepFlight,
                RaftAvatarMountedOutcome.Keep(
                    expectedCommitIdentity = committed,
                    reason = RaftAvatarMountedKeepReason.NotModified
                )
            )
        )
        assertEquals(committed, session.snapshot(mount)?.commitIdentity)

        val clearFlight = assertNotNull(session.beginFlight(mount))
        assertNotEquals(keepFlight.requestGeneration, clearFlight.requestGeneration)
        assertIs<RaftAvatarMountedResolution.RejectedCommitCas>(
            session.resolve(
                clearFlight,
                RaftAvatarMountedOutcome.Clear(
                    expectedCommitIdentity = commitIdentity(1, '0'),
                    reason = RaftAvatarMountedClearReason.ResourceAbsent
                )
            )
        )
        assertTrue(session.isCurrent(mount))
        assertEquals(committed, session.snapshot(mount)?.commitIdentity)
        assertEquals(validators, session.snapshot(mount)?.validators)
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
