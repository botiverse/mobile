package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #323: every avatar metadata store call must run inside the injected context (in production
 * the off-frame-thread SlockAvatarDbDispatcher), never on the caller's context.
 */
class DispatchingSlockAvatarCacheMetadataStoreTest {
    private val storeContext = CoroutineName("avatar-db")

    @Test
    fun everyStoreCallRunsInsideTheInjectedContext() = runBlocking(CoroutineName("frame")) {
        val delegate = ContextRecordingStore()
        val store = DispatchingSlockAvatarCacheMetadataStore(delegate, storeContext)
        val key = key()
        val guard = SlockAvatarCacheCommitGuard(key) { true }
        val empty = SlockAvatarCacheStoredState(metadata = null, failure = null)

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
        SlockAvatarCacheKey(
            SlockAvatarCacheOwner("https://api.example", "account-a", "server-a"),
            SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-a"),
            slockAvatarContentIdentity("https://images.example/avatar.png?v=1")
        )

    private class ContextRecordingStore : SlockAvatarCacheMetadataStore {
        val calls = mutableListOf<Pair<String, String?>>()

        private suspend fun record(name: String) {
            calls += name to currentCoroutineContext()[CoroutineName]?.name
        }

        override suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState {
            record("read")
            return SlockAvatarCacheStoredState(metadata = null, failure = null)
        }

        override suspend fun readCommittedForOwner(
            owner: SlockAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<SlockAvatarCacheMetadata> {
            record("readCommittedForOwner")
            return emptyList()
        }

        override suspend fun commitSuccess(
            metadata: SlockAvatarCacheMetadata,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            record("commitSuccess")
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun commitFailure(
            failure: SlockAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            record("commitFailure")
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: SlockAvatarCacheKey,
            expectedCommittedState: SlockAvatarCacheStoredState,
            previousState: SlockAvatarCacheStoredState,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            record("restoreAfterNativeFinalizeFailure")
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun clearFailure(key: SlockAvatarCacheKey) = record("clearFailure")
        override suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner) = record("clearTemporaryFailures")
        override suspend fun clearSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject) =
            record("clearSubject")
        override suspend fun clearOwner(owner: SlockAvatarCacheOwner) = record("clearOwner")
        override suspend fun clearAll() = record("clearAll")
    }
}
