package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #300: the refresh a signed-in WebView asks for ahead of its token's expiry goes through the
 * App's single-flight refresh, sends nothing when the token was already rotated, and never clears
 * the session when it is refused.
 */
class AuthDataSourceWebViewRefreshTest {
    @Test
    fun refreshesTheExpiringTokenAndStoresItsSuccessor() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(authRefreshFixtureSession("old-access", "old-refresh"))
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "old-refresh",
                responses = listOf(AuthApiResult.Success(AuthRefreshResponse("new-access", "new-refresh"))),
            )
        val source = SharedAuthDataSource(api = api, sessionStore = store)

        val result = source.refreshWebViewAccessToken("webview_token_expiring", expiringAccessToken = "old-access")

        assertTrue(result is SharedAuthResult.Success, "result=$result")
        assertEquals(1, api.refreshCalls)
        assertEquals("new-access", store.readSession()?.accessToken)
        assertEquals("new-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun aTokenAlreadyRotatedElsewhereIsNotRefreshedAgain() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(authRefreshFixtureSession("rotated-access", "rotated-refresh"))
        val api = FakeRefreshAuthApi(refreshTokenAssertion = "rotated-refresh", responses = listOf(AuthApiResult.Failure(code = 500)))
        val source = SharedAuthDataSource(api = api, sessionStore = store)

        val result = source.refreshWebViewAccessToken("webview_token_expiring", expiringAccessToken = "old-access")

        assertEquals("rotated-access", (result as? SharedAuthResult.Success)?.data?.accessToken)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun aRefusedRefreshKeepsTheSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(authRefreshFixtureSession("kept-access", "kept-refresh"))
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "kept-refresh",
                responses = listOf(AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"expired"}""")),
            )
        val source = SharedAuthDataSource(api = api, sessionStore = store)

        val result = source.refreshWebViewAccessToken("artifact_viewer_session_lost", expiringAccessToken = null)

        assertTrue(result !is SharedAuthResult.Success, "result=$result")
        assertEquals("kept-access", store.readSession()?.accessToken)
        assertEquals("kept-refresh", store.readSession()?.refreshToken)
    }
}
