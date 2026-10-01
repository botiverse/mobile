package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.model.MemberKind
import build.raft.app.shared.data.local.SlockDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAvatarCacheMetadataStoreTest {
    @Test
    fun committedForOwnerIsOwnerScopedSkipsSuppressedAndOrdersNewestFirst() = withStore { store ->
        // task #343: the cold-start prewarm reads one owner's exact committed rows, minus resources
        // still suppressed by an unexpired failure, newest validation first, bounded.
        val older = key(subject = SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-a"))
        val newer = key(subject = SlockAvatarCacheSubject.Member(MemberKind.Human, "member-b"))
        val suppressed = key(subject = SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-c"))
        val otherOwner = key(owner = owner(serverId = "server-b"))
        store.commitSuccess(metadata(older).copy(validatedAtMs = 100L), currentGuard(older))
        store.commitSuccess(metadata(newer).copy(validatedAtMs = 150L), currentGuard(newer))
        store.commitSuccess(metadata(suppressed), currentGuard(suppressed))
        store.commitSuccess(metadata(otherOwner), currentGuard(otherOwner))
        val failure =
            SlockAvatarCachePolicy.failure(
                suppressed,
                SlockAvatarCacheFailureKind.TemporaryTransport,
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
            SlockAvatarCacheCommitResult.Accepted,
            store.commitSuccess(metadata, currentGuard(key))
        )
        assertEquals(metadata, store.read(key).metadata)
        assertNull(store.read(key(owner = owner(accountId = "account-b"))).metadata)
        assertNull(store.read(key(owner = owner(serverId = "server-b"))).metadata)
        assertNull(
            store.read(
                key(subject = SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-b"))
            ).metadata
        )

        val failure =
            SlockAvatarCachePolicy.failure(
                key,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 200L
            )
        store.commitFailure(failure, retireMatchingMetadata = false, currentGuard(key))
        assertEquals(failure, store.read(key).failure)
        val otherResource = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        assertNull(store.read(otherResource).failure)
    }

    @Test
    fun gravatar404CannotSuppressOrShareBytesWithAnotherResponseQuery() = withStore { store ->
        val hash = "a".repeat(64)
        val missingKey =
            key(
                content =
                    slockAvatarContentIdentity(
                        "https://www.gravatar.com/avatar/$hash?s=160&d=404"
                    )
            )
        val fallbackKey =
            key(
                content =
                    slockAvatarContentIdentity(
                        "https://secure.gravatar.com/avatar/$hash?s=160&d=retro"
                    )
            )
        assertTrue(missingKey.nativeBytesKey != fallbackKey.nativeBytesKey)

        val missingFailure =
            SlockAvatarCachePolicy.failure(
                missingKey,
                SlockAvatarCacheFailureKind.ResourceAbsent,
                previous = null,
                nowMs = 100L
            )
        store.commitFailure(
            missingFailure,
            retireMatchingMetadata = true,
            currentGuard(missingKey)
        )

        assertEquals(missingFailure, store.read(missingKey).failure)
        assertNull(store.read(fallbackKey).failure)
        assertIs<SlockAvatarCacheReadDecision.Fetch>(
            SlockAvatarCachePolicy.decideRead(
                fallbackKey,
                store.read(fallbackKey),
                bytesAvailable = false,
                bytesDecodable = false,
                nowMs = 200L
            )
        )

        val fallbackMetadata = metadata(fallbackKey)
        store.commitSuccess(fallbackMetadata, currentGuard(fallbackKey))
        assertEquals(fallbackMetadata, store.read(fallbackKey).metadata)
        val stateObservedFromMissingKey = store.read(missingKey)
        assertEquals(fallbackMetadata, stateObservedFromMissingKey.metadata)
        assertNull(stateObservedFromMissingKey.failure)
        assertIs<SlockAvatarCacheReadDecision.Fetch>(
            SlockAvatarCachePolicy.decideRead(
                missingKey,
                stateObservedFromMissingKey,
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 150L
            )
        )
        assertIs<SlockAvatarCacheReadDecision.RenderFresh>(
            SlockAvatarCachePolicy.decideRead(
                fallbackKey,
                store.read(fallbackKey),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 150L
            )
        )
    }

    @Test
    fun successClearsSubjectFailuresAndStaleFailureCannotRetireNewerMapping() = withStore { store ->
        val oldKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=1"))
        val newKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        val oldFailure =
            SlockAvatarCachePolicy.failure(
                oldKey,
                SlockAvatarCacheFailureKind.ResourceAbsent,
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
        val oldKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=old"))
        val newKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=new"))
        val newMetadata = metadata(newKey)
        store.commitSuccess(newMetadata, currentGuard(newKey))

        assertEquals(
            SlockAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(
                metadata(oldKey),
                SlockAvatarCacheCommitGuard(newKey) { true }
            )
        )
        assertEquals(newMetadata, store.read(newKey).metadata)
    }

    @Test
    fun exactCommitCasRejectsSameGenerationWithDifferentNonce() = withStore { store ->
        val key = key()
        val current = metadata(key)
        store.commitSuccess(current, currentGuard(key))
        val wrongExpected = SlockAvatarCommitIdentity(1L, "b".repeat(64))
        val candidate =
            current.copy(
                commitIdentity = SlockAvatarCommitIdentity(2L, "c".repeat(64)),
                modelRevision = 2L,
                eTag = "must-not-commit"
            )

        assertEquals(
            SlockAvatarCacheCommitResult.RejectedStaleGeneration,
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
            SlockAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(metadata(keyA), SlockAvatarCacheCommitGuard(keyA) { false })
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
            SlockAvatarCacheCommitResult.RejectedStaleGeneration,
            store.commitSuccess(
                metadata(key),
                SlockAvatarCacheCommitGuard(key) { ++checks == 1 }
            )
        )
        assertEquals(2, checks)
        assertNull(store.read(key).metadata)

        val temporaryKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=temp"))
        val absentKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=missing"))
        store.commitFailure(
            SlockAvatarCachePolicy.failure(
                temporaryKey,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 100L
            ),
            retireMatchingMetadata = false,
            currentGuard(temporaryKey)
        )
        store.commitFailure(
            SlockAvatarCachePolicy.failure(
                absentKey,
                SlockAvatarCacheFailureKind.ResourceAbsent,
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
                SlockAvatarCachePolicy.failure(
                    key,
                    SlockAvatarCacheFailureKind.TemporaryTransport,
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
                SlockAvatarCacheCommitResult.Accepted,
                store.restoreAfterNativeFinalizeFailure(
                    key = key,
                    expectedCommittedState = expectedCommittedState,
                    previousState = previousState,
                    guard = currentGuard(key)
                )
            )
            assertEquals(previousState, store.read(key))

            val newerKey =
                key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=newer"))
            val newerMetadata = metadata(newerKey)
            store.commitSuccess(newerMetadata, currentGuard(newerKey))
            assertEquals(
                SlockAvatarCacheCommitResult.RejectedStaleGeneration,
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
            val coordinator = SlockAvatarCacheCoordinator(store) { nowMs }
            val key = key()

            assertIs<SlockAvatarCacheMutationResult.Accepted>(
                coordinator.reportSuccessfulBytes(
                    key = key,
                    sizeBytes = 512L,
                    expectedCommitIdentity = null,
                    expectedModelRevision = 0L,
                    commitIdentity = SlockAvatarCommitIdentity(1L, "1".repeat(64)),
                    modelRevision = 1L,
                    eTag = "v1",
                    lastModified = null,
                    contentType = "image/webp",
                    guard = casGuard(key, null, 0L)
                )
            )
            assertEquals(key, store.read(key).metadata?.key)

            nowMs = 200L
            assertIs<SlockAvatarCacheMutationResult.Accepted>(
                coordinator.reportFailure(
                    key,
                    SlockAvatarCacheFailureKind.TemporaryTransport,
                    currentGuard(key)
                )
            )
            assertEquals(key, store.read(key).metadata?.key)
            assertIs<SlockAvatarCacheFailure>(store.read(key).failure)

            coordinator.onNetworkChanged(key.owner)
            assertNull(store.read(key).failure)
            assertEquals(key, store.read(key).metadata?.key)

            nowMs = 300L
            assertIs<SlockAvatarCacheMutationResult.Accepted>(
                coordinator.reportFailure(
                    key,
                    SlockAvatarCacheFailureKind.ResourceAbsent,
                    currentGuard(key)
                )
            )
            assertNull(store.read(key).metadata)
            assertEquals(SlockAvatarCacheFailureKind.ResourceAbsent, store.read(key).failure?.kind)

            nowMs = 400L
            assertIs<SlockAvatarCacheMutationResult.Accepted>(
                coordinator.reportSuccessfulBytes(
                    key = key,
                    sizeBytes = 512L,
                    expectedCommitIdentity = null,
                    expectedModelRevision = 0L,
                    commitIdentity = SlockAvatarCommitIdentity(2L, "2".repeat(64)),
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
        assertNull(slockAvatarDurableCommitOrNull(1L, null, 1L))
        assertNull(slockAvatarDurableCommitOrNull(null, "a".repeat(64), 1L))
        assertNull(slockAvatarDurableCommitOrNull(1L, "a".repeat(64), null))
        assertNull(slockAvatarDurableCommitOrNull(1L, "not-a-valid-nonce", 1L))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockDatabase.Schema.create(driver).value
            val database = SlockSqlDatabase(driver)
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
                SqlDelightSlockAvatarCacheMetadataStore(database).read(key()).metadata
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
            SlockDatabase.Schema.create(driver).value
            val database = SlockSqlDatabase(driver)
            val key = key()
            val exact = metadata(key)
            SqlDelightSlockAvatarCacheMetadataStore(database)
                .commitSuccess(exact, currentGuard(key))

            val recreatedStore = SqlDelightSlockAvatarCacheMetadataStore(database)
            assertEquals(exact, recreatedStore.read(key).metadata)
            val session = SlockAvatarMountedSession()
            val token = session.mount(key)

            assertTrue(
                SlockAvatarCacheCoordinator(recreatedStore) { 150L }
                    .seedMountedSession(session, token)
            )
            assertEquals(exact.commitIdentity, session.snapshot(token)?.commitIdentity)
            assertEquals(exact.modelRevision, session.snapshot(token)?.modelRevision)
            assertEquals(exact.eTag, session.beginFlight(token)?.validators?.eTag)
        } finally {
            driver.close()
        }
    }

    private fun withStore(block: suspend (SqlDelightSlockAvatarCacheMetadataStore) -> Unit) = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockDatabase.Schema.create(driver).value
            block(SqlDelightSlockAvatarCacheMetadataStore(SlockSqlDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    private fun owner(
        environmentId: String = "https://api.example",
        accountId: String = "account-a",
        serverId: String = "server-a"
    ) = SlockAvatarCacheOwner(environmentId, accountId, serverId)

    private fun key(
        owner: SlockAvatarCacheOwner = owner(),
        subject: SlockAvatarCacheSubject = SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-a"),
        content: SlockAvatarContentIdentity = slockAvatarContentIdentity("https://images.example/avatar.png?v=1")
    ) = SlockAvatarCacheKey(owner, subject, content)

    private fun metadata(key: SlockAvatarCacheKey) =
        SlockAvatarCacheMetadata(
            key = key,
            eTag = "etag",
            fetchedAtMs = 100L,
            validatedAtMs = 100L,
            freshUntilMs = 200L,
            staleUntilMs = 300L,
            sizeBytes = 512L,
            contentType = "image/webp",
            commitIdentity = SlockAvatarCommitIdentity(1L, "a".repeat(64)),
            modelRevision = 1L
        )

    private fun currentGuard(key: SlockAvatarCacheKey) =
        SlockAvatarCacheCommitGuard(key) { true }

    private fun casGuard(
        key: SlockAvatarCacheKey,
        identity: SlockAvatarCommitIdentity?,
        modelRevision: Long
    ) = SlockAvatarCacheCommitGuard(
        key = key,
        expectedCommit = SlockAvatarCacheCommitExpectation(key, identity, modelRevision)
    ) { true }
}
