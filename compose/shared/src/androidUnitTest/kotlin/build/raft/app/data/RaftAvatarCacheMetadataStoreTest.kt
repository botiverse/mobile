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
