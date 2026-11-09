package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.model.MemberKind
import build.raft.app.shared.data.local.RaftDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAvatarCacheMetadataStoreTest {
    @Test
    fun committedForOwnerIsOwnerScopedSkipsSuppressedAndOrdersNewestFirst() = withStore { store ->
        // task #343: the cold-start prewarm reads one owner's exact committed rows, minus resources
        // still suppressed by an unexpired failure, newest validation first, bounded.
        val older = key(subject = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-a"))
        val newer = key(subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "member-b"))
        val suppressed = key(subject = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-c"))
        val otherOwner = key(owner = owner(serverId = "server-b"))
        store.commitSuccess(metadata(older).copy(validatedAtMs = 100L), currentGuard(older))
        store.commitSuccess(metadata(newer).copy(validatedAtMs = 150L), currentGuard(newer))
        store.commitSuccess(metadata(suppressed), currentGuard(suppressed))
        store.commitSuccess(metadata(otherOwner), currentGuard(otherOwner))
        val failure =
            RaftAvatarCachePolicy.failure(
                suppressed,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 200L
            )
        store.commitFailure(failure, retireMatchingMetadata = false, currentGuard(suppressed))

        val rows = store.readCommittedForOwner(owner(), nowMs = 1_000L, limit = 10L)
        assertEquals(listOf(newer, older), rows.map { it.key })
        assertTrue(rows.all { it.hasExactCommitIdentity })

        val afterRetry = store.readCommittedForOwner(owner(), nowMs = failure.retryAtMs + 1L, limit = 10L)
        assertTrue(afterRetry.any { it.key == suppressed })
        assertEquals(1, store.readCommittedForOwner(owner(), nowMs = 1_000L, limit = 1L).size)
    }

    @Test
    fun metadataAndFailuresAreOwnerSubjectAndResourceScoped() = withStore { store ->
        val key = key()
        val metadata = metadata(key)
        assertEquals(
            RaftAvatarCacheCommitResult.Accepted,
            store.commitSuccess(metadata, currentGuard(key))
        )
        assertEquals(metadata, store.read(key).metadata)
        assertNull(store.read(key(owner = owner(accountId = "account-b"))).metadata)
        assertNull(store.read(key(owner = owner(serverId = "server-b"))).metadata)
        assertNull(
            store.read(
                key(subject = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-b"))
            ).metadata
        )

        val failure =
            RaftAvatarCachePolicy.failure(
                key,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 200L
            )
        store.commitFailure(failure, retireMatchingMetadata = false, currentGuard(key))
        assertEquals(failure, store.read(key).failure)
        val otherResource = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        assertNull(store.read(otherResource).failure)
    }

    /** slock #13674: a row cached under the retired `gravatar` content kind is no cached avatar, never a crash. */
    @Test
    fun aRetiredGravatarRowReadsAsNoCachedAvatar() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftDatabase.Schema.create(driver).value
            val store = SqlDelightRaftAvatarCacheMetadataStore(RaftSqlDatabase(driver))
            val key = key(subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "member-g"))
            store.commitSuccess(metadata(key), currentGuard(key))
            driver.execute(null, "UPDATE avatarCacheMetadataEntity SET contentKind = 'gravatar'", 0)

            assertNull(store.read(key).metadata)
            assertTrue(store.readCommittedForOwner(owner(), nowMs = 1_000L, limit = 10L).isEmpty())
            assertIs<RaftAvatarCacheReadDecision.Fetch>(
                RaftAvatarCachePolicy.decideRead(key, store.read(key), bytesAvailable = false, bytesDecodable = false, nowMs = 150L)
            )
            // The next fetch's commit replaces the row.
            store.commitSuccess(metadata(key), currentGuard(key))
            assertEquals(metadata(key), store.read(key).metadata)
        } finally {
            driver.close()
        }
    }

    @Test
    fun successClearsSubjectFailuresAndStaleFailureCannotRetireNewerMapping() = withStore { store ->
        val oldKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=1"))
        val newKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        val oldFailure =
            RaftAvatarCachePolicy.failure(
                oldKey,
                RaftAvatarCacheFailureKind.ResourceAbsent,
                previous = null,
                nowMs = 100L
            )
        store.commitFailure(oldFailure, retireMatchingMetadata = true, currentGuard(oldKey))
        assertEquals(oldFailure, store.read(oldKey).failure)

        val newMetadata = metadata(newKey)
        store.commitSuccess(newMetadata, currentGuard(newKey))
        assertNull(store.read(oldKey).failure)
        assertEquals(newMetadata, store.read(newKey).metadata)

        store.commitFailure(oldFailure, retireMatchingMetadata = true, currentGuard(oldKey))
        assertEquals(newMetadata, store.read(newKey).metadata)
    }

    @Test
    fun lateSuccessCannotReuseAnotherContentGenerationsGuard() = withStore { store ->
        val oldKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=old"))
        val newKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=new"))
        val newMetadata = metadata(newKey)
        store.commitSuccess(newMetadata, currentGuard(newKey))

        assertEquals(
            RaftAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(
                metadata(oldKey),
                RaftAvatarCacheCommitGuard(newKey) { true }
            )
        )
        assertEquals(newMetadata, store.read(newKey).metadata)
    }

    @Test
    fun exactCommitCasRejectsSameGenerationWithDifferentNonce() = withStore { store ->
        val key = key()
        val current = metadata(key)
        store.commitSuccess(current, currentGuard(key))
        val wrongExpected = RaftAvatarCommitIdentity(1L, "b".repeat(64))
        val candidate =
            current.copy(
                commitIdentity = RaftAvatarCommitIdentity(2L, "c".repeat(64)),
                modelRevision = 2L,
                eTag = "must-not-commit"
            )

        assertEquals(
            RaftAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(candidate, casGuard(key, wrongExpected, 1L))
        )
        assertEquals(current, store.read(key).metadata)
    }

    @Test
    fun rejectedGuardWritesNothingAndClearOwnerDoesNotCrossPartitions() = withStore { store ->
        val ownerA = owner(accountId = "account-a")
        val ownerB = owner(accountId = "account-b")
        val keyA = key(owner = ownerA)
        val keyB = key(owner = ownerB)
        assertEquals(
            RaftAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(metadata(keyA), RaftAvatarCacheCommitGuard(keyA) { false })
        )
        assertNull(store.read(keyA).metadata)

        store.commitSuccess(metadata(keyA), currentGuard(keyA))
        store.commitSuccess(metadata(keyB), currentGuard(keyB))
        store.clearOwner(ownerA)
        assertNull(store.read(keyA).metadata)
        assertEquals(metadata(keyB), store.read(keyB).metadata)
    }

    @Test
    fun guardRevokedAfterWriteRollsBackAndNetworkChangeClearsOnlyTemporaryFailures() = withStore { store ->
        val key = key()
        var checks = 0
        assertEquals(
            RaftAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(
                metadata(key),
                RaftAvatarCacheCommitGuard(key) { ++checks == 1 }
            )
        )
        assertEquals(2, checks)
        assertNull(store.read(key).metadata)

        val temporaryKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=temp"))
        val absentKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=missing"))
        store.commitFailure(
            RaftAvatarCachePolicy.failure(
                temporaryKey,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 100L
            ),
            retireMatchingMetadata = false,
            currentGuard(temporaryKey)
        )
        store.commitFailure(
            RaftAvatarCachePolicy.failure(
                absentKey,
                RaftAvatarCacheFailureKind.ResourceAbsent,
                previous = null,
                nowMs = 100L
            ),
            retireMatchingMetadata = true,
            currentGuard(absentKey)
        )

        store.clearTemporaryFailures(key.owner)
        assertNull(store.read(temporaryKey).failure)
        assertTrue(store.read(absentKey).failure != null)
    }

    @Test
    fun nativeFinalizeCompensationRestoresExactPriorStateAndCannotEraseNewerMapping() =
        withStore { store ->
            val key = key()
            val oldMetadata = metadata(key)
            store.commitSuccess(oldMetadata, currentGuard(key))
            val oldFailure =
                RaftAvatarCachePolicy.failure(
                    key,
                    RaftAvatarCacheFailureKind.TemporaryTransport,
                    previous = null,
                    nowMs = 150L
                )
            store.commitFailure(
                oldFailure,
                retireMatchingMetadata = false,
                currentGuard(key)
            )
            val previousState = store.read(key)

            val committedMetadata =
                oldMetadata.copy(
                    eTag = "etag-new",
                    validatedAtMs = 200L,
                    freshUntilMs = 300L,
                    staleUntilMs = 400L
                )
            store.commitSuccess(committedMetadata, currentGuard(key))
            val expectedCommittedState = store.read(key)
            assertEquals(committedMetadata, expectedCommittedState.metadata)
            assertNull(expectedCommittedState.failure)

            assertEquals(
                RaftAvatarCacheCommitResult.Accepted,
                store.restoreAfterNativeFinalizeFailure(
                    key = key,
                    expectedCommittedState = expectedCommittedState,
                    previousState = previousState,
                    guard = currentGuard(key)
                )
            )
            assertEquals(previousState, store.read(key))

            val newerKey =
                key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=newer"))
            val newerMetadata = metadata(newerKey)
            store.commitSuccess(newerMetadata, currentGuard(newerKey))
            assertEquals(
                RaftAvatarCacheCommitResult.RejectedStaleGeneration,
                store.restoreAfterNativeFinalizeFailure(
                    key = key,
                    expectedCommittedState = expectedCommittedState,
                    previousState = previousState,
                    guard = currentGuard(key)
                )
            )
            assertEquals(newerMetadata, store.read(newerKey).metadata)
        }

    @Test
    fun coordinatorKeepsStaleOnTemporaryFailureRetiresOn404AndClearsSuppressionOnNetworkChange() =
        withStore { store ->
            var nowMs = 100L
            val coordinator = RaftAvatarCacheCoordinator(store) { nowMs }
            val key = key()

            assertIs<RaftAvatarCacheMutationResult.Accepted>(
                coordinator.reportSuccessfulBytes(
                    key = key,
                    sizeBytes = 512L,
                    expectedCommitIdentity = null,
                    expectedModelRevision = 0L,
                    commitIdentity = RaftAvatarCommitIdentity(1L, "1".repeat(64)),
                    modelRevision = 1L,
                    eTag = "v1",
                    lastModified = null,
                    contentType = "image/webp",
                    guard = casGuard(key, null, 0L)
                )
            )
            assertEquals(key, store.read(key).metadata?.key)

            nowMs = 200L
            assertIs<RaftAvatarCacheMutationResult.Accepted>(
                coordinator.reportFailure(
                    key,
                    RaftAvatarCacheFailureKind.TemporaryTransport,
                    currentGuard(key)
                )
            )
            assertEquals(key, store.read(key).metadata?.key)
            assertIs<RaftAvatarCacheFailure>(store.read(key).failure)

            coordinator.onNetworkChanged(key.owner)
            assertNull(store.read(key).failure)
            assertEquals(key, store.read(key).metadata?.key)

            nowMs = 300L
            assertIs<RaftAvatarCacheMutationResult.Accepted>(
                coordinator.reportFailure(
                    key,
                    RaftAvatarCacheFailureKind.ResourceAbsent,
                    currentGuard(key)
                )
            )
            assertNull(store.read(key).metadata)
            assertEquals(RaftAvatarCacheFailureKind.ResourceAbsent, store.read(key).failure?.kind)

            nowMs = 400L
            assertIs<RaftAvatarCacheMutationResult.Accepted>(
                coordinator.reportSuccessfulBytes(
                    key = key,
                    sizeBytes = 512L,
                    expectedCommitIdentity = null,
                    expectedModelRevision = 0L,
                    commitIdentity = RaftAvatarCommitIdentity(2L, "2".repeat(64)),
                    modelRevision = 2L,
                    eTag = "v2",
                    lastModified = null,
                    contentType = "image/webp",
                    guard = casGuard(key, null, 0L)
                )
            )
            assertEquals("v2", store.read(key).metadata?.eTag)
            assertNull(store.read(key).failure)
        }

    @Test
    fun databaseRejectsPartialCommitTupleAndMapperFailsClosedForInvalidTuple() = runTest {
        assertNull(raftAvatarDurableCommitOrNull(1L, null, 1L))
        assertNull(raftAvatarDurableCommitOrNull(null, "a".repeat(64), 1L))
        assertNull(raftAvatarDurableCommitOrNull(1L, "a".repeat(64), null))
        assertNull(raftAvatarDurableCommitOrNull(1L, "not-a-valid-nonce", 1L))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            database.queries.upsertAvatarCacheMetadata(
                environmentId = "https://api.example",
                accountId = "account-a",
                serverId = "server-a",
                subjectKind = "member",
                subjectId = "member-a",
                memberKind = "agent",
                contentKind = "external_url",
                contentFingerprint = "a".repeat(64),
                eTag = "legacy",
                lastModified = null,
                fetchedAtMs = 100L,
                validatedAtMs = 100L,
                freshUntilMs = 200L,
                staleUntilMs = 300L,
                sizeBytes = 512L,
                contentType = "image/webp",
                commitGeneration = 1L,
                commitNonce = null,
                modelRevision = 1L
            )
            assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM avatarCacheMetadataEntity"))

            driver.execute(
                null,
                """
                INSERT INTO avatarCacheMetadataEntity(
                  environmentId,accountId,serverId,subjectKind,subjectId,memberKind,
                  contentKind,contentFingerprint,eTag,lastModified,
                  fetchedAtMs,validatedAtMs,freshUntilMs,staleUntilMs,sizeBytes,contentType,
                  commitGeneration,commitNonce,modelRevision
                ) VALUES (
                  'https://api.example','account-a','server-a','member','member-a','agent',
                  'external_url','${key().content.fingerprint}','legacy',NULL,
                  100,100,200,300,512,'image/webp',1,'not-a-valid-nonce',1
                )
                """.trimIndent(),
                0
            ).value

            val stored =
                SqlDelightRaftAvatarCacheMetadataStore(database).read(key()).metadata
            assertEquals("legacy", stored?.eTag)
            assertNull(stored?.commitIdentity)
            assertEquals(0L, stored?.modelRevision)
        } finally {
            driver.close()
        }
    }

    @Test
    fun exactIdentitySurvivesStoreRecreationAndSeedsMountedSession() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            val key = key()
            val exact = metadata(key)
            SqlDelightRaftAvatarCacheMetadataStore(database)
                .commitSuccess(exact, currentGuard(key))

            val recreatedStore = SqlDelightRaftAvatarCacheMetadataStore(database)
            assertEquals(exact, recreatedStore.read(key).metadata)
            val session = RaftAvatarMountedSession()
            val token = session.mount(key)

            assertTrue(
                RaftAvatarCacheCoordinator(recreatedStore) { 150L }
                    .seedMountedSession(session, token)
            )
            assertEquals(exact.commitIdentity, session.snapshot(token)?.commitIdentity)
            assertEquals(exact.modelRevision, session.snapshot(token)?.modelRevision)
            assertEquals(exact.eTag, session.beginFlight(token)?.validators?.eTag)
        } finally {
            driver.close()
        }
    }

    private fun withStore(block: suspend (SqlDelightRaftAvatarCacheMetadataStore) -> Unit) = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftDatabase.Schema.create(driver).value
            block(SqlDelightRaftAvatarCacheMetadataStore(RaftSqlDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    private fun owner(
        environmentId: String = "https://api.example",
        accountId: String = "account-a",
        serverId: String = "server-a"
    ) = RaftAvatarCacheOwner(environmentId, accountId, serverId)

    private fun key(
        owner: RaftAvatarCacheOwner = owner(),
        subject: RaftAvatarCacheSubject = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-a"),
        content: RaftAvatarContentIdentity = raftAvatarContentIdentity("https://images.example/avatar.png?v=1")
    ) = RaftAvatarCacheKey(owner, subject, content)

    private fun metadata(key: RaftAvatarCacheKey) =
        RaftAvatarCacheMetadata(
            key = key,
            eTag = "etag",
            fetchedAtMs = 100L,
            validatedAtMs = 100L,
            freshUntilMs = 200L,
            staleUntilMs = 300L,
            sizeBytes = 512L,
            contentType = "image/webp",
            commitIdentity = RaftAvatarCommitIdentity(1L, "a".repeat(64)),
            modelRevision = 1L
        )

    private fun currentGuard(key: RaftAvatarCacheKey) =
        RaftAvatarCacheCommitGuard(key) { true }

    private fun casGuard(
        key: RaftAvatarCacheKey,
        identity: RaftAvatarCommitIdentity?,
        modelRevision: Long
    ) = RaftAvatarCacheCommitGuard(
        key = key,
        expectedCommit = RaftAvatarCacheCommitExpectation(key, identity, modelRevision)
    ) { true }
}
