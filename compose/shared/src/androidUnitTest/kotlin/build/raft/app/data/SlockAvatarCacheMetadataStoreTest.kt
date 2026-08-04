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
    fun coordinatorKeepsStaleOnTemporaryFailureRetiresOn404AndClearsSuppressionOnNetworkChange() =
        withStore { store ->
            var nowMs = 100L
            val coordinator = SlockAvatarCacheCoordinator(store) { nowMs }
            val key = key()

            assertIs<SlockAvatarCacheMutationResult.Accepted>(
                coordinator.reportSuccessfulBytes(
                    key = key,
                    sizeBytes = 512L,
                    eTag = "v1",
                    lastModified = null,
                    contentType = "image/webp",
                    guard = currentGuard(key)
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
                    eTag = "v2",
                    lastModified = null,
                    contentType = "image/webp",
                    guard = currentGuard(key)
                )
            )
            assertEquals("v2", store.read(key).metadata?.eTag)
            assertNull(store.read(key).failure)
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
            contentType = "image/webp"
        )

    private fun currentGuard(key: SlockAvatarCacheKey) =
        SlockAvatarCacheCommitGuard(key) { true }
}
