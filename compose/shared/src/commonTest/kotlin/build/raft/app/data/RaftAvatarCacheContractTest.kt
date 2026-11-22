package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RaftAvatarCacheContractTest {
    /** slock #13674: no Gravatar kind; a stored `gravatar` kind reads as none (no cached avatar), never an error. */
    @Test
    fun gravatarIsRetiredAsAContentKind() {
        assertNull(RaftAvatarContentKind.fromStorage("gravatar"))
        assertEquals(RaftAvatarContentKind.RaftImmutable, RaftAvatarContentKind.fromStorage("raft_immutable"))
        assertEquals(RaftAvatarContentKind.ExternalUrl, RaftAvatarContentKind.fromStorage("external_url"))
        assertEquals(
            RaftAvatarContentKind.ExternalUrl,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/${"a".repeat(64)}?s=160&d=404").kind
        )
    }

    @Test
    fun arbitraryExternalUrlRetainsSemanticQueryIdentity() {
        val first = raftAvatarContentIdentity("https://images.example/avatar.png?version=1")
        val second = raftAvatarContentIdentity("https://images.example/avatar.png?version=2")
        assertEquals(RaftAvatarContentKind.ExternalUrl, first.kind)
        assertNotEquals(first, second)
        assertEquals(
            first,
            raftAvatarContentIdentity("https://images.example/avatar.png?version=1#client-fragment")
        )
    }

    @Test
    fun nativeBytesKeyIsPartitionedByEveryOwnerAndSubjectAxis() {
        val content = raftAvatarContentIdentity("https://images.example/avatar.png?v=1")
        val baseline = key(content = content)
        assertNotEquals(
            baseline.owner.nativePartitionKey,
            owner(accountId = "account-b").nativePartitionKey
        )
        assertNotEquals(
            baseline.nativeBytesKey,
            key(owner = owner(accountId = "account-b"), content = content).nativeBytesKey
        )
        assertNotEquals(
            baseline.nativeBytesKey,
            key(owner = owner(environmentId = "https://staging.example"), content = content).nativeBytesKey
        )
        assertNotEquals(
            baseline.nativeBytesKey,
            key(owner = owner(serverId = "server-b"), content = content).nativeBytesKey
        )
        assertNotEquals(
            baseline.nativeBytesKey,
            key(subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "member-b"), content = content)
                .nativeBytesKey
        )
    }

    @Test
    fun readPolicySeparatesFreshStaleSuppressedAndExpiredStates() {
        val key = key()
        val metadata = metadata(key, validatedAtMs = 100L, freshUntilMs = 200L, staleUntilMs = 400L)
        assertIs<RaftAvatarCacheReadDecision.RenderFresh>(
            RaftAvatarCachePolicy.decideRead(
                key,
                RaftAvatarCacheStoredState(metadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 150L
            )
        )
        assertIs<RaftAvatarCacheReadDecision.RenderStaleAndRevalidate>(
            RaftAvatarCachePolicy.decideRead(
                key,
                RaftAvatarCacheStoredState(metadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 250L
            )
        )
        val failure =
            RaftAvatarCacheFailure(
                key,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                consecutiveCount = 1,
                failedAtMs = 220L,
                retryAtMs = 350L
            )
        assertIs<RaftAvatarCacheReadDecision.RenderStaleSuppressed>(
            RaftAvatarCachePolicy.decideRead(
                key,
                RaftAvatarCacheStoredState(metadata, failure),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 250L
            )
        )
        assertIs<RaftAvatarCacheReadDecision.Suppressed>(
            RaftAvatarCachePolicy.decideRead(
                key,
                RaftAvatarCacheStoredState(metadata, failure.copy(retryAtMs = 500L)),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 450L
            )
        )
    }

    @Test
    fun changedContentAndMissingOrCorruptBytesNeverRenderOldMapping() {
        val oldKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=1"))
        val nextKey = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        val oldMetadata = metadata(oldKey, validatedAtMs = 100L, freshUntilMs = 500L, staleUntilMs = 900L)
        assertIs<RaftAvatarCacheReadDecision.Fetch>(
            RaftAvatarCachePolicy.decideRead(
                nextKey,
                RaftAvatarCacheStoredState(oldMetadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 200L
            )
        )
        assertIs<RaftAvatarCacheReadDecision.Fetch>(
            RaftAvatarCachePolicy.decideRead(
                oldKey,
                RaftAvatarCacheStoredState(oldMetadata, null),
                bytesAvailable = true,
                bytesDecodable = false,
                nowMs = 200L
            )
        )
    }

    @Test
    fun failuresAreResourceScopedFiniteAndExponentiallyBounded() {
        val key = key()
        val first =
            RaftAvatarCachePolicy.failure(
                key,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 1_000L
            )
        val second =
            RaftAvatarCachePolicy.failure(
                key,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                previous = first,
                nowMs = 2_000L
            )
        var capped = second
        repeat(20) {
            capped =
                RaftAvatarCachePolicy.failure(
                    key,
                    RaftAvatarCacheFailureKind.TemporaryTransport,
                    previous = capped,
                    nowMs = capped.failedAtMs + 1L
                )
        }
        assertEquals(RaftAvatarCachePolicy.InitialTemporaryBackoffMillis, first.retryAtMs - first.failedAtMs)
        assertEquals(RaftAvatarCachePolicy.InitialTemporaryBackoffMillis * 2L, second.retryAtMs - second.failedAtMs)
        assertEquals(RaftAvatarCachePolicy.MaxTemporaryBackoffMillis, capped.retryAtMs - capped.failedAtMs)

        val missing =
            RaftAvatarCachePolicy.failure(
                key,
                RaftAvatarCacheFailureKind.ResourceAbsent,
                previous = null,
                nowMs = 5_000L
            )
        assertEquals(RaftAvatarCachePolicy.ResourceAbsentBackoffMillis, missing.retryAtMs - missing.failedAtMs)
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

    private fun metadata(
        key: RaftAvatarCacheKey,
        validatedAtMs: Long,
        freshUntilMs: Long,
        staleUntilMs: Long
    ) = RaftAvatarCacheMetadata(
        key = key,
        fetchedAtMs = validatedAtMs,
        validatedAtMs = validatedAtMs,
        freshUntilMs = freshUntilMs,
        staleUntilMs = staleUntilMs,
        sizeBytes = 256L,
        contentType = "image/webp",
        commitIdentity = RaftAvatarCommitIdentity(1L, "a".repeat(64)),
        modelRevision = 1L
    )
}
