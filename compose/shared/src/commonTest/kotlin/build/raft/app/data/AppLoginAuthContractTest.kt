package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OHOS has no kotlinx-coroutines-test KLIB; all suspend fakes complete inline and time is injected.
class AppLoginAuthContractTest {
    private class Transport : RaftAuthTransport by AuthDataSourceTest.FakeAuthApi() {
        var startBody = ""
        var completeBody = ""
        var response = AuthNetworkResponse(200,
            """{"user":{"id":"user-1","email":"user@slock.ai","displayName":"Artin"},"accessToken":"access-token","refreshToken":"refresh-token"}""")
        override suspend fun startAppLogin(bodyText: String): AuthNetworkResponse {
            startBody = bodyText
            return AuthNetworkResponse(201, """{"requestId":"request-1","loginUrl":"https://raft.example/login/app?request=request-1","expiresAt":"2026-09-30T10:00:00Z"}""")
        }
        override suspend fun completeAppLogin(bodyText: String): AuthNetworkResponse {
            completeBody = bodyText
            return response
        }
    }
    @Test fun pureLoginContractUsesNoOAuthFieldsAndCommitsCanonicalSession() = runBlocking {
        val transport = Transport()
        val store = InMemoryRaftAuthSessionStore()
        val source = SharedAuthDataSource(transport, store)
        assertTrue(source.startAppLogin("raft://login/callback", "challenge") is SharedAuthResult.Success)
        assertEquals("""{"returnUri":"raft://login/callback","codeChallenge":"challenge"}""", transport.startBody)
        assertTrue(source.completeAppLogin("handoff", "verifier") is SharedAuthResult.Success)
        assertEquals("""{"code":"handoff","codeVerifier":"verifier"}""", transport.completeBody)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("access-token", store.readSession()?.accessToken)
        assertEquals(1, store.readSession()?.servers?.size)
    }
    @Test fun malformedResponseAndEnvironmentChangeCannotInstallSession() = runBlocking {
        val transport = Transport()
        val store = InMemoryRaftAuthSessionStore()
        val source = SharedAuthDataSource(transport, store)
        assertEquals(SharedAuthResult.Unauthenticated, source.completeAppLogin("code", "verifier") { false })
        assertNull(store.readSession())
        transport.response = AuthNetworkResponse(200, """{"access_token":"legacy","refresh_token":"legacy"}""")
        assertTrue(source.completeAppLogin("code", "verifier") is SharedAuthResult.Failure)
        assertNull(store.readSession())
    }
    @Test fun environmentChangeWhileReadingPreviousSessionCannotCommit() = runBlocking {
        val backing = InMemoryRaftAuthSessionStore()
        var current = true
        val store = object : RaftAuthSessionStore by backing {
            override suspend fun readSession(): StoredAuthSession? {
                current = false
                return backing.readSession()
            }
        }
        val source = SharedAuthDataSource(Transport(), store)
        assertEquals(SharedAuthResult.Unauthenticated, source.completeAppLogin("code", "verifier") { current })
        assertNull(backing.readSession())
    }

}
