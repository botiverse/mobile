package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class RaftAvatarCacheContractTest {
    @Test
    fun immutableRaftPathIgnoresTransportQueryWhileGravatarKeepsResponseQuery() {
        val cdn =
            raftAvatarContentIdentity(
                "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp?token=one"
            )
        val api =
            raftAvatarContentIdentity(
                "https://api.raft.build/api/avatars/agents/0123456789abcdef0123456789abcdef.webp?token=two"
            )
        assertEquals(RaftAvatarContentKind.RaftImmutable, cdn.kind)
        assertEquals(cdn, api)

        val hash = "a".repeat(64)
        val missing = raftAvatarContentIdentity("https://www.gravatar.com/avatar/$hash?s=160&d=404")
        val fallback = raftAvatarContentIdentity("https://secure.gravatar.com/avatar/$hash?s=320&d=retro")
        assertEquals(RaftAvatarContentKind.Gravatar, missing.kind)
        assertNotEquals(missing, fallback)
        assertEquals(
            missing,
            raftAvatarContentIdentity(
                "https://secure.gravatar.com/avatar/$hash?s=160&d=404#client-fragment"
            )
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
    fun gravatarHashHostAndSchemeAreTransportButEveryResponseQueryParameterIsIdentity() {
        val md5 = "a".repeat(32)
        val sha256 = "b".repeat(64)
        assertEquals(
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$md5?s=80"),
            raftAvatarContentIdentity("http://secure.gravatar.com/avatar/$md5?s=80")
        )
        val baseline = raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80&d=404&r=g&f=y")
        assertNotEquals(
            baseline,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=160&d=404&r=g&f=y")
        )
        assertNotEquals(
            baseline,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80&d=retro&r=g&f=y")
        )
        assertNotEquals(
            baseline,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80&d=404&r=x&f=y")
        )
        assertNotEquals(
            baseline,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80&d=404&r=g")
        )
        assertNotEquals(
            baseline,
            raftAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80&d=404&r=g&f=y&v=2")
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
    fun nativeSubjectKeyIsOwnerScopedButStableAcrossContentVersions() {
        val baseline = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=1"))
        val changedContent = key(content = raftAvatarContentIdentity("https://images.example/avatar.png?v=2"))

        assertEquals(baseline.nativeSubjectKey, changedContent.nativeSubjectKey)
        assertNotEquals(
            baseline.nativeSubjectKey,
            key(owner = owner(serverId = "server-b")).nativeSubjectKey
        )
        assertNotEquals(
            baseline.nativeSubjectKey,
            key(subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "member-a")).nativeSubjectKey
        )
        assertNotEquals(
            baseline.nativeSubjectKey,
            key(subject = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-b")).nativeSubjectKey
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
    fun legacyMetadataWithoutExactCommitIdentityNeverRendersNativeBytes() {
        val key = key()
        val legacy =
            metadata(
                key,
                validatedAtMs = 100L,
                freshUntilMs = 500L,
                staleUntilMs = 900L
            ).copy(commitIdentity = null, modelRevision = 0L)

        assertIs<RaftAvatarCacheReadDecision.Fetch>(
            RaftAvatarCachePolicy.decideRead(
                key = key,
                stored = RaftAvatarCacheStoredState(legacy, null),
                bytesAvailable = true,
                bytesDecodable = true,
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

    @Test
    fun successfulBytesEnforceBudgetAndRefreshValidatorsWithoutChangingFetchedTime() {
        val key = key()
        assertIs<RaftAvatarCacheWriteDecision.RejectTooLarge>(
            RaftAvatarCachePolicy.successfulResponse(
                key,
                nowMs = 100L,
                sizeBytes = RaftAvatarCachePolicy.MaxEntryBytes + 1L,
                commitIdentity = RaftAvatarCommitIdentity(1L, "1".repeat(64)),
                modelRevision = 1L
            )
        )
        val stored =
            assertIs<RaftAvatarCacheWriteDecision.Store>(
                RaftAvatarCachePolicy.successfulResponse(
                    key,
                    nowMs = 100L,
                    sizeBytes = 512L,
                    commitIdentity = RaftAvatarCommitIdentity(1L, "1".repeat(64)),
                    modelRevision = 1L,
                    eTag = "v1",
                    contentType = "image/webp"
                )
            ).metadata
        val refreshed = RaftAvatarCachePolicy.notModified(stored, nowMs = 200L, eTag = "v2")
        assertEquals(100L, refreshed.fetchedAtMs)
        assertEquals(200L, refreshed.validatedAtMs)
        assertEquals("v2", refreshed.eTag)
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
