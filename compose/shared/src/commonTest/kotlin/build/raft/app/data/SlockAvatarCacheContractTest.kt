package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SlockAvatarCacheContractTest {
    @Test
    fun immutableRaftPathAndGravatarIgnoreTransportOnlyUrlChanges() {
        val cdn =
            slockAvatarContentIdentity(
                "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp?token=one"
            )
        val api =
            slockAvatarContentIdentity(
                "https://api.example.test/api/avatars/agents/0123456789abcdef0123456789abcdef.webp?token=two"
            )
        assertEquals(SlockAvatarContentKind.RaftImmutable, cdn.kind)
        assertEquals(cdn, api)

        val hash = "a".repeat(64)
        val first = slockAvatarContentIdentity("https://www.gravatar.com/avatar/$hash?s=160&d=404")
        val second = slockAvatarContentIdentity("https://secure.gravatar.com/avatar/$hash?s=320&d=retro")
        assertEquals(SlockAvatarContentKind.Gravatar, first.kind)
        assertEquals(first, second)
    }

    @Test
    fun arbitraryExternalUrlRetainsSemanticQueryIdentity() {
        val first = slockAvatarContentIdentity("https://images.example/avatar.png?version=1")
        val second = slockAvatarContentIdentity("https://images.example/avatar.png?version=2")
        assertEquals(SlockAvatarContentKind.ExternalUrl, first.kind)
        assertNotEquals(first, second)
        assertEquals(
            first,
            slockAvatarContentIdentity("https://images.example/avatar.png?version=1#client-fragment")
        )
    }

    @Test
    fun legacyAndSha256GravatarHashesUseContentIdentity() {
        val md5 = "a".repeat(32)
        val sha256 = "b".repeat(64)
        assertEquals(
            slockAvatarContentIdentity("https://www.gravatar.com/avatar/$md5?s=80"),
            slockAvatarContentIdentity("https://secure.gravatar.com/avatar/$md5?s=320")
        )
        assertEquals(
            slockAvatarContentIdentity("https://www.gravatar.com/avatar/$sha256?s=80"),
            slockAvatarContentIdentity("https://secure.gravatar.com/avatar/$sha256?s=320")
        )
    }

    @Test
    fun nativeBytesKeyIsPartitionedByEveryOwnerAndSubjectAxis() {
        val content = slockAvatarContentIdentity("https://images.example/avatar.png?v=1")
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
            key(subject = SlockAvatarCacheSubject.Member(MemberKind.Human, "member-b"), content = content)
                .nativeBytesKey
        )
    }

    @Test
    fun readPolicySeparatesFreshStaleSuppressedAndExpiredStates() {
        val key = key()
        val metadata = metadata(key, validatedAtMs = 100L, freshUntilMs = 200L, staleUntilMs = 400L)
        assertIs<SlockAvatarCacheReadDecision.RenderFresh>(
            SlockAvatarCachePolicy.decideRead(
                key,
                SlockAvatarCacheStoredState(metadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 150L
            )
        )
        assertIs<SlockAvatarCacheReadDecision.RenderStaleAndRevalidate>(
            SlockAvatarCachePolicy.decideRead(
                key,
                SlockAvatarCacheStoredState(metadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 250L
            )
        )
        val failure =
            SlockAvatarCacheFailure(
                key,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                consecutiveCount = 1,
                failedAtMs = 220L,
                retryAtMs = 350L
            )
        assertIs<SlockAvatarCacheReadDecision.RenderStaleSuppressed>(
            SlockAvatarCachePolicy.decideRead(
                key,
                SlockAvatarCacheStoredState(metadata, failure),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 250L
            )
        )
        assertIs<SlockAvatarCacheReadDecision.Suppressed>(
            SlockAvatarCachePolicy.decideRead(
                key,
                SlockAvatarCacheStoredState(metadata, failure.copy(retryAtMs = 500L)),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 450L
            )
        )
    }

    @Test
    fun changedContentAndMissingOrCorruptBytesNeverRenderOldMapping() {
        val oldKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=1"))
        val nextKey = key(content = slockAvatarContentIdentity("https://images.example/avatar.png?v=2"))
        val oldMetadata = metadata(oldKey, validatedAtMs = 100L, freshUntilMs = 500L, staleUntilMs = 900L)
        assertIs<SlockAvatarCacheReadDecision.Fetch>(
            SlockAvatarCachePolicy.decideRead(
                nextKey,
                SlockAvatarCacheStoredState(oldMetadata, null),
                bytesAvailable = true,
                bytesDecodable = true,
                nowMs = 200L
            )
        )
        assertIs<SlockAvatarCacheReadDecision.Fetch>(
            SlockAvatarCachePolicy.decideRead(
                oldKey,
                SlockAvatarCacheStoredState(oldMetadata, null),
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
            SlockAvatarCachePolicy.failure(
                key,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                previous = null,
                nowMs = 1_000L
            )
        val second =
            SlockAvatarCachePolicy.failure(
                key,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                previous = first,
                nowMs = 2_000L
            )
        var capped = second
        repeat(20) {
            capped =
                SlockAvatarCachePolicy.failure(
                    key,
                    SlockAvatarCacheFailureKind.TemporaryTransport,
                    previous = capped,
                    nowMs = capped.failedAtMs + 1L
                )
        }
        assertEquals(SlockAvatarCachePolicy.InitialTemporaryBackoffMillis, first.retryAtMs - first.failedAtMs)
        assertEquals(SlockAvatarCachePolicy.InitialTemporaryBackoffMillis * 2L, second.retryAtMs - second.failedAtMs)
        assertEquals(SlockAvatarCachePolicy.MaxTemporaryBackoffMillis, capped.retryAtMs - capped.failedAtMs)

        val missing =
            SlockAvatarCachePolicy.failure(
                key,
                SlockAvatarCacheFailureKind.ResourceAbsent,
                previous = null,
                nowMs = 5_000L
            )
        assertEquals(SlockAvatarCachePolicy.ResourceAbsentBackoffMillis, missing.retryAtMs - missing.failedAtMs)
    }

    @Test
    fun successfulBytesEnforceBudgetAndRefreshValidatorsWithoutChangingFetchedTime() {
        val key = key()
        assertIs<SlockAvatarCacheWriteDecision.RejectTooLarge>(
            SlockAvatarCachePolicy.successfulResponse(
                key,
                nowMs = 100L,
                sizeBytes = SlockAvatarCachePolicy.MaxEntryBytes + 1L
            )
        )
        val stored =
            assertIs<SlockAvatarCacheWriteDecision.Store>(
                SlockAvatarCachePolicy.successfulResponse(
                    key,
                    nowMs = 100L,
                    sizeBytes = 512L,
                    eTag = "v1",
                    contentType = "image/webp"
                )
            ).metadata
        val refreshed = SlockAvatarCachePolicy.notModified(stored, nowMs = 200L, eTag = "v2")
        assertEquals(100L, refreshed.fetchedAtMs)
        assertEquals(200L, refreshed.validatedAtMs)
        assertEquals("v2", refreshed.eTag)
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

    private fun metadata(
        key: SlockAvatarCacheKey,
        validatedAtMs: Long,
        freshUntilMs: Long,
        staleUntilMs: Long
    ) = SlockAvatarCacheMetadata(
        key = key,
        fetchedAtMs = validatedAtMs,
        validatedAtMs = validatedAtMs,
        freshUntilMs = freshUntilMs,
        staleUntilMs = staleUntilMs,
        sizeBytes = 256L,
        contentType = "image/webp"
    )
}
