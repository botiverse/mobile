package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeAuthorityProviderV12Test {

    @Test
    fun typedAuthEventsDriveLeaseGrantFlowsWithoutResurrectingReleasedScope() = runBlocking {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "provider-process",
            )
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider =
            RealtimeAuthorityProvider(
                registry = registry,
                authStore = auth,
                authEvents = auth,
                currentEnvironmentId = { "env-test" },
            )
        provider.start(lifecycleScope)

        provider.registerScope("shell-a")
        provider.registerScope("shell-b")
        assertNull(provider.authorityFlow("shell-a").value)
        assertNull(provider.authorityFlow("shell-b").value)

        val session = session(token = "same-token")
        auth.replace(session, AuthSessionEvent.Authenticated(generation = 1))
        val a1 = assertNotNull(provider.authorityFlow("shell-a").value)
        val b1 = assertNotNull(provider.authorityFlow("shell-b").value)
        val b1Grant = assertNotNull(b1.grant("server-b"))
        assertEquals("same-token", b1.accessToken)
        assertEquals(setOf("server-a", "server-b"), b1.grantsByServer.keys)

        assertTrue(provider.releaseScope("shell-a"))
        assertNull(provider.authorityFlow("shell-a").value)
        val bAfterSiblingRelease = assertNotNull(provider.authorityFlow("shell-b").value)
        assertEquals(b1.witness, bAfterSiblingRelease.witness)
        assertEquals(b1Grant, bAfterSiblingRelease.grant("server-b"))
        assertTrue(registry.runIfCurrentGrant(b1Grant) {})
        assertFalse(registry.runIfCurrentGrant(assertNotNull(a1.grant("server-a"))) {})

        auth.replace(null, AuthSessionEvent.Cleared(generation = 2, reason = AuthSessionClearReason.Logout))
        assertNull(provider.authorityFlow("shell-a").value)
        assertNull(provider.authorityFlow("shell-b").value)
        assertFalse(registry.runIfCurrentGrant(b1Grant) {})

        // Same principal, same servers and deliberately the same access token: the typed
        // auth generation still creates a fresh authority family. Compose must be able to
        // observe this even though its old server/token effect keys would be identical.
        auth.replace(session, AuthSessionEvent.Authenticated(generation = 3))
        assertNull(provider.authorityFlow("shell-a").value, "released scope must stay dead")
        val b2 = assertNotNull(provider.authorityFlow("shell-b").value)
        val b2Grant = assertNotNull(b2.grant("server-b"))
        assertNotEquals(b1.witness.connectionFamilyId, b2.witness.connectionFamilyId)
        assertNotEquals(b1Grant.connectionEpochId, b2Grant.connectionEpochId)
        assertTrue(registry.runIfCurrentGrant(b2Grant) {})

        auth.replace(session, AuthSessionEvent.TokensRotated(generation = 4))
        val b3 = assertNotNull(provider.authorityFlow("shell-b").value)
        val b3Grant = assertNotNull(b3.grant("server-b"))
        assertNotEquals(b2.witness.connectionFamilyId, b3.witness.connectionFamilyId)
        assertFalse(registry.runIfCurrentGrant(b2Grant) {})
        assertTrue(registry.runIfCurrentGrant(b3Grant) {})

        lifecycleScope.cancel()
    }

    @Test
    fun staleTypedEventIsIgnoredAndCurrentSessionIsRereadInsteadOfTrustedFromPayload() = runBlocking {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "provider-reread",
            )
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(lifecycleScope)
        provider.registerScope("shell")

        auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 10))
        val first = assertNotNull(provider.authorityFlow("shell").value)

        // The wake contains no credentials or membership. Change durable session first and
        // then emit the wake; provider must reread the store and publish token-2/server-c.
        auth.replace(
            session(token = "token-2", servers = listOf("server-c")),
            AuthSessionEvent.BindingMetadataChanged(generation = 11),
        )
        val second = assertNotNull(provider.authorityFlow("shell").value)
        assertEquals("token-2", second.accessToken)
        assertEquals(setOf("server-c"), second.grantsByServer.keys)
        assertNotEquals(first.witness.connectionFamilyId, second.witness.connectionFamilyId)

        auth.emitOnly(AuthSessionEvent.Authenticated(generation = 10))
        assertEquals(second, provider.authorityFlow("shell").value, "lower wake generation is stale")
        lifecycleScope.cancel()
    }

    @Test
    fun delayedOldReleaseCannotDeleteSuccessorRegistrationForSameScopeKey() = runBlocking {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "provider-registration-aba",
            )
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(lifecycleScope)
        auth.replace(session(token = "token"), AuthSessionEvent.Authenticated(generation = 1))

        val leaseOne = provider.registerScope("shell", registrationId = "mount-1")
        val first = assertNotNull(provider.authorityFlow("shell").value)
        val leaseTwo = provider.registerScope("shell", registrationId = "mount-2")
        val successor = assertNotNull(provider.authorityFlow("shell").value)
        assertNotEquals(leaseOne.id, leaseTwo.id)
        assertNotEquals(first.witness.scopeLeaseId, successor.witness.scopeLeaseId)

        assertFalse(provider.releaseScope("shell", registrationId = "mount-1"))
        assertEquals(successor, provider.authorityFlow("shell").value)
        assertTrue(provider.releaseScope("shell", registrationId = "mount-2"))
        assertNull(provider.authorityFlow("shell").value)
        lifecycleScope.cancel()
    }

    @Test
    fun blankPrincipalFailsClosedInsteadOfPublishingUsableAuthority() = runBlocking {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "provider-invalid-principal",
            )
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(lifecycleScope)
        provider.registerScope("shell")

        auth.replace(
            session(token = "token").copy(user = StoredAuthUser(id = "  ", email = "invalid@example.test")),
            AuthSessionEvent.Authenticated(generation = 1),
        )
        assertNull(provider.authorityFlow("shell").value)
        assertNull(registry.snapshot.value.authRevision.session)
        lifecycleScope.cancel()
    }

    private class FakeTypedAuthSessionStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun readSession(): StoredAuthSession? = session

        override suspend fun saveSession(session: StoredAuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession(reason: String, source: String) {
            session = null
        }

        suspend fun replace(next: StoredAuthSession?, event: AuthSessionEvent) {
            session = next
            events.emit(event)
        }

        suspend fun emitOnly(event: AuthSessionEvent) {
            events.emit(event)
        }
    }

    private fun session(
        token: String,
        servers: List<String> = listOf("server-a", "server-b"),
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = token,
            refreshToken = "refresh",
            selectedServerId = servers.firstOrNull(),
            servers = servers.map { id -> StoredAuthServer(id = id, name = id, slug = id) },
        )
}
