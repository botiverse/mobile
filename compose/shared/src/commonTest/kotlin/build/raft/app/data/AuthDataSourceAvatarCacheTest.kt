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
            val sessionStore = InMemorySlockAuthSessionStore(oldSession)
            val metadata = AuthAvatarMetadataStore()
            val native = AuthAvatarNativeCache()
            val runtime =
                SlockAvatarCacheRuntime(
                    coordinator = SlockAvatarCacheCoordinator(metadata) { 100L },
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

            val oldOwner = SlockAvatarCacheOwner("https://old.example/api", "old-account", "old-server")
            assertEquals(listOf(oldOwner.nativePartitionKey), native.retiredOwners)
            assertEquals(listOf(oldOwner), metadata.clearedOwners)
        }
    }

    @Test
    fun sameAccountSessionRefreshDoesNotRetireItsOwnerPartition() = runBlocking {
        val sessionStore =
            InMemorySlockAuthSessionStore(
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
                    SlockAvatarCacheRuntime(
                        coordinator = SlockAvatarCacheCoordinator(metadata) { 100L },
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

    private class AuthAvatarMetadataStore : SlockAvatarCacheMetadataStore {
        val clearedOwners = mutableListOf<SlockAvatarCacheOwner>()

        override suspend fun readCommittedForOwner(
            owner: SlockAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<SlockAvatarCacheMetadata> = emptyList()

        override suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState =
            SlockAvatarCacheStoredState(null, null)

        override suspend fun commitSuccess(
            metadata: SlockAvatarCacheMetadata,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun commitFailure(
            failure: SlockAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: SlockAvatarCacheKey,
            expectedCommittedState: SlockAvatarCacheStoredState,
            previousState: SlockAvatarCacheStoredState,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun clearFailure(key: SlockAvatarCacheKey) = Unit

        override suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner) = Unit

        override suspend fun clearSubject(
            owner: SlockAvatarCacheOwner,
            subject: SlockAvatarCacheSubject
        ) = Unit

        override suspend fun clearOwner(owner: SlockAvatarCacheOwner) {
            clearedOwners += owner
        }

        override suspend fun clearAll() = Unit
    }

    private class AuthAvatarNativeCache : SlockAvatarNativeCache {
        val retiredOwners = mutableListOf<String>()

        override suspend fun inspect(
            nativePartitionKey: String,
            nativeSubjectKey: String,
            nativeBytesKey: String
        ): SlockAvatarNativeInspection = SlockAvatarNativeInspection.Unsupported

        override suspend fun awaitResult(request: SlockAvatarNativeRequest): SlockAvatarNativeLoadResult =
            SlockAvatarNativeLoadResult.Unsupported

        override suspend fun finalize(
            request: SlockAvatarNativeRequest,
            outcome: SlockAvatarNativeFinalizeOutcome
        ): SlockAvatarNativeMutationResult = SlockAvatarNativeMutationResult.Accepted

        override suspend fun rollback(request: SlockAvatarNativeRequest): SlockAvatarNativeMutationResult =
            SlockAvatarNativeMutationResult.Accepted

        override suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeMutationResult {
            retiredOwners += nativePartitionKey
            return SlockAvatarNativeMutationResult.Accepted
        }

        override suspend fun retireSubject(
            nativePartitionKey: String,
            nativeSubjectKey: String
        ): SlockAvatarNativeMutationResult = SlockAvatarNativeMutationResult.Accepted
    }
}
