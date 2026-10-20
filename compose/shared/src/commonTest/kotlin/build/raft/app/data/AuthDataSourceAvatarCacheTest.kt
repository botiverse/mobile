package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AuthDataSourceAvatarCacheTest {
    @Test
    fun terminalLogoutAndEveryAccountReplacementPathRetireTheCapturedOldOwner() = runBlocking {
        val actions =
            listOf<suspend (SharedAuthDataSource) -> Unit>(
                { source -> assertTrue(source.logout() is SharedAuthResult.Success) },
                { source -> assertTrue(source.login("user@slock.ai", "password") is SharedAuthResult.Success) },
                { source ->
                    assertTrue(source.completeSocialLogin("google", "code") is SharedAuthResult.Success)
                },
                { source ->
                    assertTrue(
                        source.completeMobileOAuthLogin("code", "verifier") is SharedAuthResult.Success
                    )
                },
                { source -> assertTrue(source.register("new@slock.ai", "password") is SharedAuthResult.Success) }
            )

        actions.forEach { action ->
            val oldSession = oldSession()
            val sessionStore = InMemoryRaftAuthSessionStore(oldSession)
            val metadata = AuthAvatarMetadataStore()
            val native = AuthAvatarNativeCache()
            val runtime =
                RaftAvatarCacheRuntime(
                    coordinator = RaftAvatarCacheCoordinator(metadata) { 100L },
                    currentOwner = { null }
                )
            val source =
                SharedAuthDataSource(
                    transport = AuthDataSourceTest.FakeAuthApi(),
                    sessionStore = sessionStore
                ).withAvatarCacheRuntime(
                    runtime = runtime,
                    nativeCache = native,
                    environmentId = { "https://old.example/api/" }
                )

            action(source)

            val oldOwner = RaftAvatarCacheOwner("https://old.example/api", "old-account", "old-server")
            assertEquals(listOf(oldOwner.nativePartitionKey), native.retiredOwners)
            assertEquals(listOf(oldOwner), metadata.clearedOwners)
        }
    }

    @Test
    fun sameAccountSessionRefreshDoesNotRetireItsOwnerPartition() = runBlocking {
        val sessionStore =
            InMemoryRaftAuthSessionStore(
                oldSession().copy(user = StoredAuthUser(id = "user-1", email = "user@slock.ai"))
            )
        val metadata = AuthAvatarMetadataStore()
        val native = AuthAvatarNativeCache()
        val source =
            SharedAuthDataSource(
                transport = AuthDataSourceTest.FakeAuthApi(),
                sessionStore = sessionStore
            ).withAvatarCacheRuntime(
                runtime =
                    RaftAvatarCacheRuntime(
                        coordinator = RaftAvatarCacheCoordinator(metadata) { 100L },
                        currentOwner = { null }
                    ),
                nativeCache = native,
                environmentId = { "https://old.example/api/" }
            )

        assertTrue(source.completeMobileOAuthLogin("code", "verifier") is SharedAuthResult.Success)

        assertTrue(native.retiredOwners.isEmpty())
        assertTrue(metadata.clearedOwners.isEmpty())
    }

    private fun oldSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "old-account", email = "old@example.test"),
            accessToken = "old-access",
            refreshToken = "old-refresh",
            selectedServerId = "old-server",
            servers = listOf(StoredAuthServer(id = "old-server", name = "Old"))
        )

    private class AuthAvatarMetadataStore : RaftAvatarCacheMetadataStore {
        val clearedOwners = mutableListOf<RaftAvatarCacheOwner>()

        override suspend fun readCommittedForOwner(
            owner: RaftAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<RaftAvatarCacheMetadata> = emptyList()

        override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
            RaftAvatarCacheStoredState(null, null)

        override suspend fun commitSuccess(
            metadata: RaftAvatarCacheMetadata,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun commitFailure(
            failure: RaftAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: RaftAvatarCacheKey,
            expectedCommittedState: RaftAvatarCacheStoredState,
            previousState: RaftAvatarCacheStoredState,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun clearFailure(key: RaftAvatarCacheKey) = Unit

        override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) = Unit

        override suspend fun clearSubject(
            owner: RaftAvatarCacheOwner,
            subject: RaftAvatarCacheSubject
        ) = Unit

        override suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
            clearedOwners += owner
        }

        override suspend fun clearAll() = Unit
    }

    private class AuthAvatarNativeCache : RaftAvatarNativeCache {
        val retiredOwners = mutableListOf<String>()

        override suspend fun inspect(
            nativePartitionKey: String,
            nativeSubjectKey: String,
            nativeBytesKey: String
        ): RaftAvatarNativeInspection = RaftAvatarNativeInspection.Unsupported

        override suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult =
            RaftAvatarNativeLoadResult.Unsupported

        override suspend fun finalize(
            request: RaftAvatarNativeRequest,
            outcome: RaftAvatarNativeFinalizeOutcome
        ): RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted

        override suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult =
            RaftAvatarNativeMutationResult.Accepted

        override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult {
            retiredOwners += nativePartitionKey
            return RaftAvatarNativeMutationResult.Accepted
        }

        override suspend fun retireSubject(
            nativePartitionKey: String,
            nativeSubjectKey: String
        ): RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted
    }
}
