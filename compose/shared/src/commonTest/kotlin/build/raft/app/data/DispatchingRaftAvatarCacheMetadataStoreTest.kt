package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #323: every avatar metadata store call must run inside the injected context (in production
 * the off-frame-thread RaftAvatarDbDispatcher), never on the caller's context.
 */
class DispatchingRaftAvatarCacheMetadataStoreTest {
    private val storeContext = CoroutineName("avatar-db")

    @Test
    fun everyStoreCallRunsInsideTheInjectedContext() = runBlocking(CoroutineName("frame")) {
        val delegate = ContextRecordingStore()
        val store = DispatchingRaftAvatarCacheMetadataStore(delegate, storeContext)
        val key = key()
        val guard = RaftAvatarCacheCommitGuard(key) { true }
        val empty = RaftAvatarCacheStoredState(metadata = null, failure = null)

        store.read(key)
        store.readCommittedForOwner(key.owner, nowMs = 0L, limit = 1L)
        store.restoreAfterNativeFinalizeFailure(key, empty, empty, guard)
        store.clearFailure(key)
        store.clearTemporaryFailures(key.owner)
        store.clearSubject(key.owner, key.subject)
        store.clearOwner(key.owner)
        store.clearAll()

        assertEquals(
            listOf(
                "read", "readCommittedForOwner", "restoreAfterNativeFinalizeFailure", "clearFailure", "clearTemporaryFailures",
                "clearSubject", "clearOwner", "clearAll"
            ),
            delegate.calls.map { it.first }
        )
        delegate.calls.forEach { (call, contextName) ->
            assertEquals("avatar-db", contextName, "$call ran outside the store context")
        }
        assertEquals("frame", currentCoroutineContext()[CoroutineName]?.name, "caller context must be restored")
    }

    private fun key() =
        RaftAvatarCacheKey(
            RaftAvatarCacheOwner("https://api.example", "account-a", "server-a"),
            RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-a"),
            raftAvatarContentIdentity("https://images.example/avatar.png?v=1")
        )

    private class ContextRecordingStore : RaftAvatarCacheMetadataStore {
        val calls = mutableListOf<Pair<String, String?>>()

        private suspend fun record(name: String) {
            calls += name to currentCoroutineContext()[CoroutineName]?.name
        }

        override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState {
            record("read")
            return RaftAvatarCacheStoredState(metadata = null, failure = null)
        }

        override suspend fun readCommittedForOwner(
            owner: RaftAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<RaftAvatarCacheMetadata> {
            record("readCommittedForOwner")
            return emptyList()
        }

        override suspend fun commitSuccess(
            metadata: RaftAvatarCacheMetadata,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            record("commitSuccess")
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun commitFailure(
            failure: RaftAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            record("commitFailure")
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: RaftAvatarCacheKey,
            expectedCommittedState: RaftAvatarCacheStoredState,
            previousState: RaftAvatarCacheStoredState,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            record("restoreAfterNativeFinalizeFailure")
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun clearFailure(key: RaftAvatarCacheKey) = record("clearFailure")
        override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) = record("clearTemporaryFailures")
        override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) =
            record("clearSubject")
        override suspend fun clearOwner(owner: RaftAvatarCacheOwner) = record("clearOwner")
        override suspend fun clearAll() = record("clearAll")
    }
}
